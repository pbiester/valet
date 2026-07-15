package dev.isonet.valet.core;

/**
 * Starts Boundary sessions. The real implementation ({@link ProcessBoundaryCli}) shells
 * out to the {@code boundary} CLI; tests substitute a fake so {@link SessionManager} is
 * exercisable without a subprocess or a controller.
 *
 * <p>Note the port is <em>not</em> a parameter: allocating a free loopback port and
 * retrying on a bind race is an implementation concern of the subprocess (§8.1), so it is
 * owned by the CLI, not the caller.
 */
public interface BoundaryCli {

    /**
     * Start a Boundary session for {@code key} and return once its session JSON has been
     * read. The backing proxy process stays alive after this returns.
     *
     * @throws ValetException with an appropriate SQLState if the session cannot be started
     */
    LiveSession connect(TargetKey key) throws ValetException;
}
