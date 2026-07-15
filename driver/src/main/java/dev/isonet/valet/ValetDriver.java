package dev.isonet.valet;

import dev.isonet.valet.core.BoundaryCli;
import dev.isonet.valet.core.BoundarySession;
import dev.isonet.valet.core.BrokeredCredential;
import dev.isonet.valet.core.CliDiscovery;
import dev.isonet.valet.core.LiveSession;
import dev.isonet.valet.core.ProcessBoundaryCli;
import dev.isonet.valet.core.SessionManager;
import dev.isonet.valet.core.SqlStates;
import dev.isonet.valet.core.TargetKey;
import dev.isonet.valet.core.ValetException;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The {@link Driver} for {@code jdbc:boundary://} URLs.
 *
 * <p>On {@link #connect}, Valet acquires (or reuses) a shared Boundary session for the
 * target, then opens a real PostgreSQL connection through the session's local proxy port
 * using the brokered credentials, and hands back a wrapped {@link Connection}. The caller
 * never sees a port, a password or a session.
 */
public final class ValetDriver implements Driver {

    private static final int VERSION_MAJOR = 0;
    private static final int VERSION_MINOR = 1;
    private static final Logger LOG = Logger.getLogger("dev.isonet.valet");

    /**
     * One shared session manager per JVM so sessions are reused across every connection and
     * pool. It is built lazily from the first connection's configuration (CLI path, timeouts);
     * later connections that specify different values reuse the existing manager.
     */
    private static volatile SessionManager sharedManager;
    private static final Object MANAGER_LOCK = new Object();

    private static volatile Driver postgresDriver;

    static {
        try {
            DriverManager.registerDriver(new ValetDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        // Prefix check only — never throw from acceptsURL (§4).
        return url != null && url.startsWith(ValetUrl.SCHEME_PREFIX);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;                       // not ours — let DriverManager try the next driver
        }

        ValetUrl parsed = ValetUrl.parse(url);
        SessionManager manager = manager(parsed);
        TargetKey key = new TargetKey(parsed.controllerAddr(), parsed.scopeName(), parsed.targetName());

        SessionManager.Handle handle = manager.acquire(key);
        try {
            Connection delegate = openDelegate(parsed, key, handle.session(), info);
            return ConnectionWrapper.wrap(delegate, manager, handle);
        } catch (SQLException | RuntimeException e) {
            manager.release(handle);           // undo the acquire ref if opening PG failed
            throw e;
        }
    }

    private Connection openDelegate(ValetUrl url, TargetKey key, LiveSession session, Properties callerInfo)
            throws SQLException {
        BoundarySession data = session.data();
        String host = (data.address() == null || data.address().isBlank()) ? "127.0.0.1" : data.address();
        String delegateUrl = "jdbc:postgresql://" + host + ":" + data.port()
                + "/" + encodePathSegment(url.database()) + url.passThroughQuery();

        Properties props = new Properties();
        if (callerInfo != null) {
            props.putAll(callerInfo);
        }

        if (url.brokeredCredentials()) {
            BrokeredCredential cred = data.selectUsernamePassword(url.credentialName(), key.describe());
            // Override whatever the caller passed — JetBrains IDEs may send user="" (§9).
            props.setProperty("user", cred.username());
            props.setProperty("password", cred.password());
        }

        Driver pg = postgresDriver();
        Connection connection = pg.connect(delegateUrl, props);
        if (connection == null) {
            throw new ValetException("The PostgreSQL driver did not accept the delegate URL "
                    + delegateUrl + ".", SqlStates.UNABLE_TO_CONNECT);
        }
        return connection;
    }

    private static SessionManager manager(ValetUrl url) throws ValetException {
        SessionManager existing = sharedManager;
        if (existing != null) {
            return existing;
        }
        synchronized (MANAGER_LOCK) {
            if (sharedManager != null) {
                return sharedManager;
            }
            String cliBinary = CliDiscovery.discover(url.cliPath()).toString();
            // §8.6: set the CLI's -inactive-timeout just above the driver's idle timeout so
            // Boundary only reaps genuinely orphaned processes (e.g. from a crashed JVM).
            Duration inactiveTimeout = url.idleTimeout().plusSeconds(30);
            BoundaryCli cli = new ProcessBoundaryCli(cliBinary, url.connectTimeout(), inactiveTimeout);
            sharedManager = new SessionManager(cli, url.idleTimeout());
            return sharedManager;
        }
    }

    /**
     * Instantiate {@code org.postgresql.Driver} by class name via reflection — never
     * {@code DriverManager.getDriver} (§9). Isolated per-driver classloaders (JetBrains IDEs,
     * DBeaver) plus shaded service files are exactly where {@code DriverManager} becomes
     * unpredictable; bypass it.
     */
    private static Driver postgresDriver() throws ValetException {
        Driver pg = postgresDriver;
        if (pg != null) {
            return pg;
        }
        synchronized (MANAGER_LOCK) {
            if (postgresDriver != null) {
                return postgresDriver;
            }
            try {
                Class<?> clazz = Class.forName("org.postgresql.Driver");
                postgresDriver = (Driver) clazz.getDeclaredConstructor().newInstance();
                return postgresDriver;
            } catch (ReflectiveOperationException e) {
                throw new ValetException("The PostgreSQL JDBC driver (org.postgresql.Driver) is not on "
                        + "the classpath. Add org.postgresql:postgresql to your dependencies, or use the "
                        + "Valet bundle JAR which includes it.", SqlStates.UNABLE_TO_CONNECT, e);
            }
        }
    }

    /** RFC 3986 percent-encoding of a path segment (space → %20), so PGJDBC decodes it back. */
    static String encodePathSegment(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        DriverPropertyInfo[] props = new DriverPropertyInfo[6];
        props[0] = describe("boundary.addr", "Boundary controller URL (defaults to $BOUNDARY_ADDR).");
        props[1] = describe("boundary.cli-path", "Absolute path to the boundary CLI (defaults to discovery).");
        props[2] = describe("boundary.connect-timeout", "How long to wait for a session (default 30s).");
        props[3] = describe("boundary.idle-timeout", "Grace before an unused session is killed (default 60s).");
        props[4] = describe("boundary.credential-name", "Disambiguate multiple brokered credentials.");
        DriverPropertyInfo brokered =
                describe("boundary.brokered-credentials", "false = use caller credentials (default true).");
        brokered.choices = new String[]{"true", "false"};
        props[5] = brokered;
        return props;
    }

    private static DriverPropertyInfo describe(String name, String description) {
        DriverPropertyInfo info = new DriverPropertyInfo(name, null);
        info.description = description;
        info.required = false;
        return info;
    }

    @Override
    public int getMajorVersion() {
        return VERSION_MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return VERSION_MINOR;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;         // a brokering wrapper, not a compliant driver in its own right
    }

    @Override
    public Logger getParentLogger() {
        return LOG;
    }
}
