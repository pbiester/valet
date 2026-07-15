package dev.isonet.valet;

import dev.isonet.valet.core.LiveSession;
import dev.isonet.valet.core.SessionManager;
import dev.isonet.valet.core.SqlStates;
import dev.isonet.valet.core.ValetException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A dynamic {@link Proxy} around the real PostgreSQL {@link Connection} that (a) decrements
 * the shared session's ref count exactly once when the connection is closed (§7), and (b)
 * remaps a failure caused by the Boundary session ending to an actionable SQLState (§10).
 *
 * <p>Reflection overhead is irrelevant next to network I/O. Load-bearing details:
 * <ul>
 *   <li>{@code close()} is idempotent — a double close must not double-decrement.</li>
 *   <li>Release goes through the {@link SessionManager.Handle}, so it targets the exact lease
 *       this connection incremented rather than whatever lease currently sits at the key.</li>
 *   <li>{@code unwrap}/{@code isWrapperFor} are handled explicitly, or
 *       {@code conn.unwrap(PGConnection.class)} breaks for users.</li>
 *   <li>If the Boundary session has ended, a delegate {@link SQLException} is remapped to
 *       {@code 08006} with a clear "open a new connection" message instead of surfacing
 *       PGJDBC's raw "connection reset".</li>
 * </ul>
 */
final class ConnectionWrapper implements InvocationHandler {

    private final Connection delegate;
    private final SessionManager sessionManager;
    private final SessionManager.Handle handle;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ConnectionWrapper(Connection delegate, SessionManager sessionManager, SessionManager.Handle handle) {
        this.delegate = delegate;
        this.sessionManager = sessionManager;
        this.handle = handle;
    }

    static Connection wrap(Connection delegate, SessionManager sessionManager, SessionManager.Handle handle) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionWrapper.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionWrapper(delegate, sessionManager, handle));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "close":
                if (closed.compareAndSet(false, true)) {
                    try {
                        delegate.close();
                    } finally {
                        sessionManager.release(handle);   // decrement exactly this lease, once
                    }
                }
                return null;

            case "isClosed":
                return closed.get() || (Boolean) invokeDelegate(method, args);

            case "unwrap": {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(proxy)) {
                    return proxy;                        // we ARE a Connection
                }
                return delegate.unwrap(iface);           // e.g. org.postgresql.PGConnection
            }

            case "isWrapperFor": {
                Class<?> iface = (Class<?>) args[0];
                if (iface.isInstance(proxy)) {
                    return Boolean.TRUE;
                }
                return delegate.isWrapperFor(iface);
            }

            case "hashCode":
                return System.identityHashCode(proxy);

            case "equals":
                return proxy == args[0];

            case "toString":
                return "ValetConnection[" + handle.targetKey().describe() + ", delegate=" + delegate + "]";

            default:
                return invokeDelegate(method, args);
        }
    }

    private Object invokeDelegate(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            // A connection failure once the Boundary session has ended is not a query error —
            // surface it as 08006 so the caller (or pool) knows to open a fresh connection.
            if (cause instanceof SQLException && sessionEnded()) {
                throw new ValetException(
                        "The Boundary session for " + handle.targetKey().describe()
                                + " has ended, so this connection is no longer usable; open a new connection.",
                        SqlStates.CONNECTION_FAILURE, cause);
            }
            throw cause;
        }
    }

    /** Whether the backing Boundary session has ended — process gone, or past its expiration. */
    private boolean sessionEnded() {
        LiveSession session = handle.session();
        if (!session.isAlive()) {
            return true;
        }
        Instant expiration = session.data().expiration();
        return expiration != null && !Instant.now().isBefore(expiration);
    }
}
