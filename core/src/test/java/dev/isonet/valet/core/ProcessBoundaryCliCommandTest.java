package dev.isonet.valet.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Command construction — no subprocess, so this runs on every OS. */
class ProcessBoundaryCliCommandTest {

    private final ProcessBoundaryCli cli =
            new ProcessBoundaryCli("boundary", Duration.ofSeconds(10), Duration.ofSeconds(90));

    @Test
    void resolvesByNameWhenNoTargetId() {
        List<String> cmd = cli.buildCommand(new TargetKey("https://c", "proj", "db-target"), 55000);
        assertTrue(cmd.contains("-target-scope-name=proj"));
        assertTrue(cmd.contains("-target-name=db-target"));
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("-target-id=")));
        assertTrue(cmd.contains("-listen-port=55000"));
        assertTrue(cmd.contains("-addr=https://c"));
    }

    @Test
    void resolvesByIdWhenTargetIdPresent() {
        List<String> cmd = cli.buildCommand(TargetKey.byId("https://c", "ttcp_1234567890"), 55000);
        assertTrue(cmd.contains("-target-id=ttcp_1234567890"));
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("-target-scope-name=")));
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("-target-name=")));
    }

    @Test
    void omitsAddrWhenControllerAddrNull() {
        List<String> cmd = cli.buildCommand(new TargetKey(null, "proj", "db-target"), 55000);
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("-addr=")));
    }
}
