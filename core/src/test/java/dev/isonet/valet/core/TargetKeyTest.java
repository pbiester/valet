package dev.isonet.valet.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetKeyTest {

    @Test
    void nameBasedKeysShareIdentityByAddrScopeTarget() {
        TargetKey a = new TargetKey("https://c", "scope", "target");
        TargetKey b = new TargetKey("https://c", "scope", "target");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void idBasedKeysShareIdentityByAddrAndId() {
        TargetKey a = TargetKey.byId("https://c", "ttcp_abc");
        TargetKey b = TargetKey.byId("https://c", "ttcp_abc");
        assertEquals(a, b, "same controller + id must be one cached session");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void idBasedAndNameBasedAreDistinct() {
        assertNotEquals(TargetKey.byId("https://c", "ttcp_abc"),
                new TargetKey("https://c", "scope", "target"));
    }

    @Test
    void describeReflectsTheResolutionMode() {
        assertTrue(TargetKey.byId("https://c", "ttcp_abc").describe().contains("ttcp_abc"));
        assertTrue(new TargetKey("https://c", "scope", "target").describe().contains("'target'"));
        assertTrue(new TargetKey(null, "scope", "target").describe().contains("$BOUNDARY_ADDR"));
    }
}
