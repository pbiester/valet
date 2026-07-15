package dev.isonet.valet.core;

/**
 * A Boundary session plus control over the process backing it.
 *
 * <p>{@link BoundaryCli#connect} returns this rather than a bare {@link BoundarySession}
 * because the session cache must, without blocking, ask whether the proxy is still alive
 * ({@link #isAlive()}) and be able to tear it down ({@link #kill()}). {@link #kill()} must
 * be idempotent.
 */
public interface LiveSession {

    /** The parsed session data (address, proxy port, brokered credentials, …). */
    BoundarySession data();

    /** Whether the backing proxy process is still running. Must not block. */
    boolean isAlive();

    /** Tear down the backing proxy process. Idempotent; safe to call from any thread. */
    void kill();
}
