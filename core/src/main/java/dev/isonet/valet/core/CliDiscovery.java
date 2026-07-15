package dev.isonet.valet.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Locates the {@code boundary} binary (§8.7).
 *
 * <p>A JetBrains IDE (or any GUI client) launched from Dock/Finder/Start Menu does not
 * inherit the shell {@code PATH}, so {@code /opt/homebrew/bin} and friends are invisible.
 * This is the single biggest support burden if skipped, hence the explicit well-known
 * directories and the {@code $VALET_BOUNDARY_CLI} escape hatch.
 *
 * <p>Search order: {@code boundary.cli-path} param → {@code $VALET_BOUNDARY_CLI} →
 * {@code PATH} → well-known dirs → (Windows) {@code C:\Program Files\Boundary}. The first
 * candidate that answers {@code boundary version} with exit 0 wins; the result is cached
 * and logged once at INFO. On failure the exception lists every path searched.
 */
public final class CliDiscovery {

    static final String ENV_OVERRIDE = "VALET_BOUNDARY_CLI";

    private static final Logger LOG = Logger.getLogger("dev.isonet.valet");

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String BINARY = WINDOWS ? "boundary.exe" : "boundary";

    private static final AtomicReference<Path> DISCOVERED = new AtomicReference<>();
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    private CliDiscovery() {}

    /**
     * Resolve the boundary binary, verifying it runs. When no explicit {@code cliPathParam}
     * is given the result is cached process-wide (the common case), so discovery runs at
     * most once.
     */
    public static Path discover(String cliPathParam) throws ValetException {
        boolean cacheable = isBlank(cliPathParam);
        if (cacheable) {
            Path cached = DISCOVERED.get();
            if (cached != null) {
                return cached;
            }
        }
        Path resolved = resolve(cliPathParam, System::getenv, defaultWellKnownDirs(),
                CliDiscovery::verifyByRunning);
        if (cacheable) {
            DISCOVERED.compareAndSet(null, resolved);
        }
        if (LOGGED.compareAndSet(false, true)) {
            LOG.info(() -> "Using boundary CLI at " + resolved);
        }
        return resolved;
    }

    /**
     * Core resolution logic, decoupled from {@code System.getenv} and the real process
     * check so it is unit-testable.
     */
    static Path resolve(String cliPathParam,
                        Function<String, String> env,
                        List<Path> wellKnownDirs,
                        Predicate<Path> verifier) throws ValetException {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();

        if (!isBlank(cliPathParam)) {
            candidates.add(Path.of(cliPathParam.trim()));
        }
        String override = env.apply(ENV_OVERRIDE);
        if (!isBlank(override)) {
            candidates.add(Path.of(override.trim()));
        }
        String path = env.apply("PATH");
        if (!isBlank(path)) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!isBlank(dir)) {
                    candidates.add(Path.of(dir).resolve(BINARY));
                }
            }
        }
        for (Path dir : wellKnownDirs) {
            candidates.add(dir.resolve(BINARY));
        }

        for (Path candidate : candidates) {
            if (verifier.test(candidate)) {
                return candidate;
            }
        }

        StringBuilder sb = new StringBuilder()
                .append("Could not find a working '").append(BINARY).append("' executable. ")
                .append("Install the Boundary CLI, or set boundary.cli-path in the JDBC URL ")
                .append("or the $").append(ENV_OVERRIDE).append(" environment variable. Searched:");
        for (Path candidate : candidates) {
            sb.append("\n  - ").append(candidate);
        }
        throw new ValetException(sb.toString(), SqlStates.UNABLE_TO_CONNECT);
    }

    static List<Path> defaultWellKnownDirs() {
        List<Path> dirs = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (WINDOWS) {
            dirs.add(Path.of("C:\\Program Files\\Boundary"));
        } else {
            dirs.add(Path.of("/opt/homebrew/bin"));
            dirs.add(Path.of("/usr/local/bin"));
            dirs.add(Path.of("/usr/bin"));
            if (home != null && !home.isBlank()) {
                dirs.add(Path.of(home, ".local", "bin"));
            }
        }
        return dirs;
    }

    /** Runs {@code <binary> version}; exit 0 means usable. This waitFor is fine — it is a */
    /* short-lived check, unlike the long-lived proxy which must never be waited on (§8.5). */
    private static boolean verifyByRunning(Path binary) {
        if (!Files.isRegularFile(binary)) {
            return false;
        }
        Process proc = null;
        try {
            proc = new ProcessBuilder(binary.toString(), "version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                proc.destroyForcibly();
            }
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Test hook: clear the process-wide cache so discovery re-runs. */
    static void resetCacheForTests() {
        DISCOVERED.set(null);
        LOGGED.set(false);
    }
}
