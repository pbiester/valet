package dev.isonet.valet.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliDiscoveryTest {

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void explicitParamIsPreferredWhenItVerifies(@TempDir Path tmp) throws Exception {
        Path binary = tmp.resolve("boundary-custom");
        Path resolved = CliDiscovery.resolve(binary.toString(), env(Map.of()), List.of(),
                candidate -> candidate.equals(binary));
        assertEquals(binary, resolved);
    }

    @Test
    void environmentOverrideIsUsedWhenNoParam(@TempDir Path tmp) throws Exception {
        Path binary = tmp.resolve("boundary-from-env");
        Path resolved = CliDiscovery.resolve(null,
                env(Map.of(CliDiscovery.ENV_OVERRIDE, binary.toString())), List.of(),
                candidate -> candidate.equals(binary));
        assertEquals(binary, resolved);
    }

    @Test
    void pathIsSearchedWhenNoParamOrEnv(@TempDir Path tmp) throws Exception {
        Path resolved = CliDiscovery.resolve(null, env(Map.of("PATH", tmp.toString())), List.of(),
                candidate -> tmp.equals(candidate.getParent()));
        assertEquals(tmp, resolved.getParent());
    }

    @Test
    void wellKnownDirectoriesAreSearchedLast(@TempDir Path tmp) throws Exception {
        Path resolved = CliDiscovery.resolve(null, env(Map.of()), List.of(tmp),
                candidate -> tmp.equals(candidate.getParent()));
        assertEquals(tmp, resolved.getParent());
    }

    @Test
    void searchOrderPrefersParamOverEnvOverPath(@TempDir Path tmp) throws Exception {
        Path param = tmp.resolve("a-param");
        Path fromEnv = tmp.resolve("b-env");
        // Both would verify; the param must win because it is tried first.
        Predicate<Path> anythingUnderTmp = candidate -> candidate.startsWith(tmp);
        Path resolved = CliDiscovery.resolve(param.toString(),
                env(Map.of(CliDiscovery.ENV_OVERRIDE, fromEnv.toString(), "PATH", tmp.toString())),
                List.of(tmp), anythingUnderTmp);
        assertEquals(param, resolved);
    }

    @Test
    void throwsListingEverySearchedPathWhenNothingVerifies(@TempDir Path tmp) {
        Path param = tmp.resolve("nope");
        ValetException e = assertThrows(ValetException.class, () ->
                CliDiscovery.resolve(param.toString(),
                        env(Map.of("PATH", tmp.toString())), List.of(tmp), candidate -> false));
        assertEquals(SqlStates.UNABLE_TO_CONNECT, e.getSQLState());
        assertTrue(e.getMessage().contains(param.toString()), "message should list the searched param path");
        assertTrue(e.getMessage().contains("Searched:"));
    }
}
