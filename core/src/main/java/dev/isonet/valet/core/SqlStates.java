package dev.isonet.valet.core;

/**
 * JDBC {@code SQLState} codes Valet maps its failures to (§10). Using standard classes
 * (class 08 = connection exception, 28 = invalid authorization) means clients that
 * special-case SQLState still behave sensibly.
 */
public final class SqlStates {

    /** Boundary auth token missing or expired. Message tells the user to re-authenticate. */
    public static final String NOT_AUTHENTICATED = "28000";

    /**
     * Unable to establish a connection: CLI not found, target/scope not found, or the
     * session JSON did not arrive before the connect timeout.
     */
    public static final String UNABLE_TO_CONNECT = "08001";

    /** A previously established connection failed — e.g. the session expired mid-use. */
    public static final String CONNECTION_FAILURE = "08006";

    /** Server rejected the connection: {@code connection_limit == 1}, so pooling is impossible. */
    public static final String CONNECTION_REJECTED = "08004";

    /** Malformed {@code jdbc:boundary://} URL. */
    public static final String INVALID_URL = "42000";

    private SqlStates() {}
}
