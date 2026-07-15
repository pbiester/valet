package dev.isonet.valet.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Starts Boundary sessions by shelling out to {@code boundary connect} (§8).
 *
 * <p>Eight things, each of which is a day lost if missed:
 * <ol>
 *   <li>Allocate an explicit {@code -listen-port} — targets often set the real PG port as
 *       {@code default_client_port}, so an unqualified connect collides with a local
 *       Postgres and with itself (§8.1). Retry on a bind race; trust the JSON's port.</li>
 *   <li>Stream one JSON value with Jackson — never {@code readLine} (§8.2).</li>
 *   <li>Keep draining stdout after parsing, or the proxy wedges when the pipe fills — the
 *       worst bug in the project (§8.3).</li>
 *   <li>Stderr into a bounded ring buffer for error messages (§8.4).</li>
 *   <li>Never {@code waitFor} on the proxy — staying alive is success (§8.5).</li>
 *   <li>Set {@code -inactive-timeout} as a server-side backstop for orphaned processes (§8.6).</li>
 *   <li>Discovery handled by {@link CliDiscovery} (§8.7).</li>
 *   <li>Redact credentials everywhere (§8.8) — handled in the domain records.</li>
 * </ol>
 */
public final class ProcessBoundaryCli implements BoundaryCli {

    private static final int MAX_BIND_ATTEMPTS = 3;
    private static final int STDERR_RING_LINES = 50;

    private final String cliBinary;
    private final Duration connectTimeout;
    private final Duration inactiveTimeout;

    /**
     * @param cliBinary       resolved boundary executable (see {@link CliDiscovery})
     * @param connectTimeout  how long to wait for the session JSON
     * @param inactiveTimeout value for {@code -inactive-timeout}; set just above the driver's
     *                        idle timeout so Boundary only reaps genuinely orphaned processes (§8.6)
     */
    public ProcessBoundaryCli(String cliBinary, Duration connectTimeout, Duration inactiveTimeout) {
        this.cliBinary = Objects.requireNonNull(cliBinary, "cliBinary");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.inactiveTimeout = Objects.requireNonNull(inactiveTimeout, "inactiveTimeout");
    }

    @Override
    public LiveSession connect(TargetKey key) throws ValetException {
        ValetException lastBindFailure = null;
        for (int attempt = 1; attempt <= MAX_BIND_ATTEMPTS; attempt++) {
            int port = allocateLoopbackPort(key);
            try {
                return startSession(key, port);
            } catch (BindConflict conflict) {
                // §8.1 TOCTOU: the port was free when we closed our probe socket but boundary
                // lost the race to bind it. Try again with a fresh port.
                lastBindFailure = conflict.toValetException();
            }
        }
        if (lastBindFailure != null) {
            throw lastBindFailure;
        }
        throw new ValetException("Could not start a Boundary session for " + key.describe()
                + " after " + MAX_BIND_ATTEMPTS + " attempts.", SqlStates.UNABLE_TO_CONNECT);
    }

    private LiveSession startSession(TargetKey key, int port) throws ValetException, BindConflict {
        Process proc = launch(key, port);

        RingBuffer stderr = new RingBuffer(STDERR_RING_LINES);
        Thread stderrPump = daemon("valet-stderr-" + port, () -> pumpLines(proc.getErrorStream(), stderr));
        stderrPump.start();

        InputStream stdout = proc.getInputStream();

        // §8.2: read exactly one JSON value, on a worker thread so we can bound the wait.
        CompletableFuture<BoundarySession> parsed = new CompletableFuture<>();
        Thread parseThread = daemon("valet-parse-" + port, () -> {
            try {
                parsed.complete(SessionParser.readOne(stdout));
            } catch (Throwable t) {
                parsed.completeExceptionally(t);
            }
        });
        parseThread.start();

        BoundarySession data;
        try {
            data = parsed.get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            proc.destroy();
            awaitDeath(proc, stderrPump);
            throw classifyFailure(key, stderr,
                    "Timed out after " + connectTimeout.toSeconds() + "s waiting for a Boundary session");
        } catch (ExecutionException e) {
            proc.destroy();
            awaitDeath(proc, stderrPump);
            if (looksLikeBindConflict(stderr)) {
                throw new BindConflict(key, port, stderr.snapshot());
            }
            throw classifyFailure(key, stderr, "Boundary CLI produced no valid session JSON");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new ValetException("Interrupted while starting a Boundary session for "
                    + key.describe() + ".", SqlStates.UNABLE_TO_CONNECT, e);
        }

        // §5: a limit-1 session cannot back a pool; fail loudly rather than intermittently.
        if (!data.allowsPooling()) {
            proc.destroyForcibly();
            throw new ValetException("Boundary " + key.describe()
                    + " reports connection_limit=1, so a connection pool cannot share this session. "
                    + "Raise the target's session connection limit (-1 = unlimited).",
                    SqlStates.CONNECTION_REJECTED);
        }

        // §8.3: hand the rest of stdout to a daemon drain immediately, or the proxy wedges.
        daemon("valet-drain-" + port, () -> drain(stdout)).start();

        ProcessLiveSession live = new ProcessLiveSession(proc, data, stderr);
        // §8.5: never waitFor; learn of death via onExit and mark the session doomed proactively.
        proc.onExit().thenRun(live::markExited);
        return live;
    }

    private Process launch(TargetKey key, int port) throws ValetException {
        try {
            return new ProcessBuilder(buildCommand(key, port))
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new ValetException("Failed to launch the boundary CLI (" + cliBinary + "): "
                    + e.getMessage() + ".", SqlStates.UNABLE_TO_CONNECT, e);
        }
    }

    List<String> buildCommand(TargetKey key, int port) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliBinary);
        cmd.add("connect");
        if (key.targetId() != null) {
            cmd.add("-target-id=" + key.targetId());
        } else {
            cmd.add("-target-scope-name=" + key.scopeName());
            cmd.add("-target-name=" + key.targetName());
        }
        if (key.controllerAddr() != null && !key.controllerAddr().isBlank()) {
            cmd.add("-addr=" + key.controllerAddr());
        }
        cmd.add("-listen-port=" + port);
        cmd.add("-format=json");
        long seconds = Math.max(1, inactiveTimeout.toSeconds());
        cmd.add("-inactive-timeout=" + seconds + "s");
        return cmd;
    }

    /** §8.1: bind an ephemeral loopback port, then release it for boundary to claim. */
    private static int allocateLoopbackPort(TargetKey key) throws ValetException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new ValetException("Could not allocate a local proxy port for " + key.describe()
                    + ": " + e.getMessage() + ".", SqlStates.UNABLE_TO_CONNECT, e);
        }
    }

    private ValetException classifyFailure(TargetKey key, RingBuffer stderr, String prefix) {
        String tail = stderr.snapshot();
        String lower = tail.toLowerCase(Locale.ROOT);
        boolean authProblem = containsAny(lower,
                "unauthenticated", "unauthorized", "authenticate", "token", "401");
        String suffix = tail.isBlank() ? "" : "\n--- boundary stderr (last lines) ---\n" + tail;

        if (authProblem) {
            String addr = key.controllerAddr() == null ? "" : " -addr=" + key.controllerAddr();
            return new ValetException(prefix + " — not authenticated to Boundary. "
                    + "Run: boundary authenticate" + addr + suffix, SqlStates.NOT_AUTHENTICATED);
        }
        return new ValetException(prefix + " for " + key.describe() + "." + suffix,
                SqlStates.UNABLE_TO_CONNECT);
    }

    /** Error path only: give the dying process a moment to flush stderr (success never waits). */
    private static void awaitDeath(Process proc, Thread stderrPump) {
        try {
            proc.waitFor(1, TimeUnit.SECONDS);
            stderrPump.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    private static boolean looksLikeBindConflict(RingBuffer stderr) {
        String s = stderr.snapshot().toLowerCase(Locale.ROOT);
        return s.contains("address already in use")
                || s.contains("already in use")
                || (s.contains("listen") && s.contains("bind"));
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static void pumpLines(InputStream in, RingBuffer ring) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ring.add(line);
            }
        } catch (IOException ignored) {
            // Stream closed on teardown — nothing to do.
        }
    }

    private static void drain(InputStream in) {
        byte[] buffer = new byte[8192];
        try (in) {
            while (in.read(buffer) != -1) {
                // discard — we only read to keep the pipe from filling (§8.3)
            }
        } catch (IOException ignored) {
            // Stream closed on teardown.
        }
    }

    private static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    /** A {@link LiveSession} backed by a running {@code boundary connect} proxy process. */
    static final class ProcessLiveSession implements LiveSession {
        private final Process process;
        private final BoundarySession data;
        private final RingBuffer stderr;
        private volatile boolean exited;

        ProcessLiveSession(Process process, BoundarySession data, RingBuffer stderr) {
            this.process = process;
            this.data = data;
            this.stderr = stderr;
        }

        void markExited() {
            exited = true;
        }

        @Override
        public BoundarySession data() {
            return data;
        }

        @Override
        public boolean isAlive() {
            return !exited && process.isAlive();
        }

        @Override
        public void kill() {
            process.destroy();
            process.destroyForcibly();
        }

        /** Last lines of stderr — for post-mortem diagnostics if a session dies mid-use. */
        String stderrTail() {
            return stderr.snapshot();
        }
    }

    /** Bounded FIFO of the last N stderr lines (§8.4) — never {@code DISCARD}, never unbounded. */
    static final class RingBuffer {
        private final int max;
        private final ArrayDeque<String> lines;

        RingBuffer(int max) {
            this.max = max;
            this.lines = new ArrayDeque<>(max);
        }

        synchronized void add(String line) {
            if (lines.size() == max) {
                lines.pollFirst();
            }
            lines.addLast(line);
        }

        synchronized String snapshot() {
            return String.join("\n", lines);
        }
    }

    /** Internal signal that boundary lost the bind race; triggers a retry with a new port. */
    private static final class BindConflict extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient TargetKey key;
        private final int port;
        private final String stderrTail;

        BindConflict(TargetKey key, int port, String stderrTail) {
            this.key = key;
            this.port = port;
            this.stderrTail = stderrTail;
        }

        ValetException toValetException() {
            String suffix = stderrTail.isBlank() ? "" : "\n" + stderrTail;
            return new ValetException("boundary could not bind local proxy port " + port + " for "
                    + key.describe() + " (all retries exhausted)." + suffix,
                    SqlStates.UNABLE_TO_CONNECT);
        }
    }
}
