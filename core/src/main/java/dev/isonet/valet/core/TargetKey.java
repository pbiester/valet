package dev.isonet.valet.core;

import java.util.Objects;

/**
 * Identity of a Boundary target for session sharing.
 *
 * <p>Two connections with the same identity share one Boundary session. The database name
 * and credential selection are applied per <em>connection</em> (at the PostgreSQL layer),
 * never per session — a single TCP proxy serves every database on the target — so they are
 * deliberately absent from this key.
 *
 * <p>A target is identified one of two ways:
 * <ul>
 *   <li><b>By name</b> (the common case): {@code scopeName} + {@code targetName}, resolved by
 *       the CLI via {@code -target-scope-name}/{@code -target-name}.</li>
 *   <li><b>By id</b> (the {@code boundary.target-id} escape hatch, §2.5): a {@code ttcp_…}
 *       target id resolved via {@code -target-id}, for deployments where name resolution is
 *       ambiguous or awkward. When set, the scope/target names are not part of the identity.</li>
 * </ul>
 *
 * <p>{@code controllerAddr} may be {@code null}: the CLI then falls back to
 * {@code $BOUNDARY_ADDR}. Two connections that both omit it hash equal.
 */
public record TargetKey(String controllerAddr, String scopeName, String targetName, String targetId) {

    public TargetKey {
        if (targetId == null) {
            Objects.requireNonNull(scopeName, "scopeName");
            Objects.requireNonNull(targetName, "targetName");
        }
    }

    /** Name-based key (the common case). */
    public TargetKey(String controllerAddr, String scopeName, String targetName) {
        this(controllerAddr, scopeName, targetName, null);
    }

    /** Id-based key: resolve by {@code ttcp_…} target id, bypassing scope/target-name resolution. */
    public static TargetKey byId(String controllerAddr, String targetId) {
        return new TargetKey(controllerAddr, null, null, Objects.requireNonNull(targetId, "targetId"));
    }

    /** Human phrasing for error messages. */
    public String describe() {
        String addr = controllerAddr == null ? "$BOUNDARY_ADDR" : controllerAddr;
        if (targetId != null) {
            return "target " + targetId + " on " + addr;
        }
        return "target '" + targetName + "' in scope '" + scopeName + "' on " + addr;
    }
}
