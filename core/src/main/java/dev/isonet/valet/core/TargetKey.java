package dev.isonet.valet.core;

import java.util.Objects;

/**
 * Identity of a Boundary target for session sharing.
 *
 * <p>Two connections with the same controller address, scope name and target name share
 * one Boundary session. The database name and credential selection are applied per
 * <em>connection</em> (at the PostgreSQL layer), never per session — a single TCP proxy
 * serves every database on the target — so they are deliberately absent from this key.
 *
 * <p>{@code controllerAddr} may be {@code null}: the CLI then falls back to
 * {@code $BOUNDARY_ADDR}. Two connections that both omit it hash equal.
 */
public record TargetKey(String controllerAddr, String scopeName, String targetName) {

    public TargetKey {
        Objects.requireNonNull(scopeName, "scopeName");
        Objects.requireNonNull(targetName, "targetName");
    }

    /** Human phrasing for error messages, e.g. {@code target 'db' in scope 'proj' on https://…}. */
    public String describe() {
        String addr = controllerAddr == null ? "$BOUNDARY_ADDR" : controllerAddr;
        return "target '" + targetName + "' in scope '" + scopeName + "' on " + addr;
    }
}
