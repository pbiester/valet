package dev.isonet.valet.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Drives {@link ProcessBoundaryCli} against a fake {@code boundary} shell script (§12).
 *
 * <p>These tests spawn a POSIX shell script, so they run on Linux and macOS only; the
 * OS-independent parsing/concurrency behaviour is covered elsewhere. On Windows CI these
 * are skipped rather than claimed.
 */
class ProcessBoundaryCliTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final String COMPACT_JSON = "{\"address\":\"127.0.0.1\",\"port\":63102,"
            + "\"protocol\":\"tcp\",\"connection_limit\":-1,\"session_id\":\"s_EXAMPLE00001\","
            + "\"credentials\":[{\"credential_source\":{\"name\":\"example-credential\","
            + "\"credential_type\":\"username_password\"},"
            + "\"credential\":{\"username\":\"example-user\",\"password\":\"example-password\"}}]}";

    private static TargetKey key() {
        return new TargetKey("https://boundary.example", "project-alpha", "orders-db");
    }

    private static ProcessBoundaryCli cli(Path script, Duration connectTimeout) {
        return new ProcessBoundaryCli(script.toString(), connectTimeout, Duration.ofSeconds(90));
    }

    private static Path writeScript(Path dir, String body) throws IOException {
        Path script = dir.resolve("fake-boundary.sh");
        Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        assertTrue(script.toFile().setExecutable(true), "script must be executable");
        return script;
    }

    private static Path writeJson(Path dir, String name, String json) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void parsesPrettyJsonAndStaysAlive(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        byte[] pretty = getClass().getResourceAsStream("/fixtures/session.json").readAllBytes();
        Path json = tmp.resolve("pretty.json");
        Files.write(json, pretty);
        Path script = writeScript(tmp, "cat \"" + json + "\"\nsleep 30\n");

        LiveSession session = cli(script, Duration.ofSeconds(10)).connect(key());
        try {
            assertEquals(63102, session.data().port());
            assertEquals("example-user", session.data().credentials().get(0).username());
            assertTrue(session.isAlive(), "the proxy process must stay alive after parsing");
        } finally {
            session.kill();
        }
    }

    @Test
    void parsesCompactJson(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        Path json = writeJson(tmp, "compact.json", COMPACT_JSON);
        Path script = writeScript(tmp, "cat \"" + json + "\"\nsleep 30\n");

        LiveSession session = cli(script, Duration.ofSeconds(10)).connect(key());
        try {
            assertEquals(63102, session.data().port());
        } finally {
            session.kill();
        }
    }

    @Test
    void keepsDrainingStdoutAfterTheJsonSoTheProxyNeverWedges(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        // §8.3 — the worst bug: after the JSON the proxy keeps writing. If we stop reading,
        // the ~64KB pipe fills and the process blocks forever. Here the script emits 1MB after
        // the JSON and then exits; it can only reach exit if we drained everything.
        Path json = writeJson(tmp, "compact.json", COMPACT_JSON);
        Path script = writeScript(tmp,
                "cat \"" + json + "\"\n"
                        + "yes ABCDEFGHIJKLMNOPQRSTUVWXYZ | head -c 1048576 2>/dev/null\n"
                        + "exit 0\n");

        LiveSession session = cli(script, Duration.ofSeconds(10)).connect(key());
        assertEquals(63102, session.data().port());

        long deadline = System.currentTimeMillis() + 10_000;
        while (session.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(session.isAlive(),
                "process must exit once its post-JSON stdout is fully drained (no wedge)");
    }

    @Test
    void mapsAuthFailureTo28000(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        Path script = writeScript(tmp,
                "echo 'Error: unauthenticated, please run: boundary authenticate' 1>&2\nexit 1\n");

        ValetException e = assertThrows(ValetException.class,
                () -> cli(script, Duration.ofSeconds(10)).connect(key()));
        assertEquals(SqlStates.NOT_AUTHENTICATED, e.getSQLState());
        assertTrue(e.getMessage().contains("boundary authenticate"));
    }

    @Test
    void mapsGenericEarlyExitTo08001WithStderrTail(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        Path script = writeScript(tmp,
                "echo 'Error: target named orders-db not found in scope project-alpha' 1>&2\nexit 1\n");

        ValetException e = assertThrows(ValetException.class,
                () -> cli(script, Duration.ofSeconds(10)).connect(key()));
        assertEquals(SqlStates.UNABLE_TO_CONNECT, e.getSQLState());
        assertTrue(e.getMessage().contains("not found"), "the stderr tail should be surfaced");
    }

    @Test
    void timesOutWhenNoJsonArrives(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        Path script = writeScript(tmp, "sleep 30\n");   // hangs, never emits JSON

        ValetException e = assertThrows(ValetException.class,
                () -> cli(script, Duration.ofMillis(700)).connect(key()));
        assertEquals(SqlStates.UNABLE_TO_CONNECT, e.getSQLState());
        assertTrue(e.getMessage().toLowerCase(Locale.ROOT).contains("timed out"));
    }

    @Test
    void mapsGarbageOutputTo08001(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        Path script = writeScript(tmp, "echo 'this is not json at all >>>'\nexit 0\n");

        ValetException e = assertThrows(ValetException.class,
                () -> cli(script, Duration.ofSeconds(10)).connect(key()));
        assertEquals(SqlStates.UNABLE_TO_CONNECT, e.getSQLState());
    }

    @Test
    void rejectsConnectionLimitOfOneWith08004(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        String limitOne = COMPACT_JSON.replace("\"connection_limit\":-1", "\"connection_limit\":1");
        Path json = writeJson(tmp, "limit1.json", limitOne);
        Path script = writeScript(tmp, "cat \"" + json + "\"\nsleep 30\n");

        ValetException e = assertThrows(ValetException.class,
                () -> cli(script, Duration.ofSeconds(10)).connect(key()));
        assertEquals(SqlStates.CONNECTION_REJECTED, e.getSQLState());
        assertTrue(e.getMessage().contains("connection_limit=1"));
    }

    @Test
    void retriesOnBindConflictThenFails(@TempDir Path tmp) throws Exception {
        assumeFalse(WINDOWS);
        Path count = tmp.resolve("attempts.log");
        Files.writeString(count, "");
        Path script = writeScript(tmp,
                "echo x >> \"" + count + "\"\n"
                        + "echo 'Error: listen tcp 127.0.0.1:5432: bind: address already in use' 1>&2\n"
                        + "exit 1\n");

        ValetException e = assertThrows(ValetException.class,
                () -> cli(script, Duration.ofSeconds(10)).connect(key()));
        assertEquals(SqlStates.UNABLE_TO_CONNECT, e.getSQLState());
        assertTrue(e.getMessage().toLowerCase(Locale.ROOT).contains("bind"));

        List<String> attempts = Files.readAllLines(count);
        assertEquals(3, attempts.size(), "a bind race should be retried up to 3 times (§8.1)");
    }
}
