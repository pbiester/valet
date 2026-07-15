package dev.isonet.valet;

import dev.isonet.valet.core.BoundaryCli;
import dev.isonet.valet.core.BoundarySession;
import dev.isonet.valet.core.LiveSession;
import dev.isonet.valet.core.SessionManager;
import dev.isonet.valet.core.SessionManager.Handle;
import dev.isonet.valet.core.SqlStates;
import dev.isonet.valet.core.TargetKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionWrapperTest {

    private static final TargetKey KEY =
            new TargetKey("https://boundary.example", "project-alpha", "orders-db");

    /** A {@link Connection} whose relevant methods are observable and can be made to fail. */
    private static final class FakeDelegate implements InvocationHandler {
        int closeCount = 0;
        boolean closed = false;
        boolean wrapperForResult = false;
        String throwOnMethod = null;      // method name that should throw
        String throwSqlState = null;      // SQLState of the thrown SQLException

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
            if (method.getName().equals(throwOnMethod)) {
                throw new SQLException("simulated failure", throwSqlState);
            }
            switch (method.getName()) {
                case "close":
                    closeCount++;
                    closed = true;
                    return null;
                case "isClosed":
                    return closed;
                case "unwrap":
                    return "UNWRAPPED:" + ((Class<?>) args[0]).getSimpleName();
                case "isWrapperFor":
                    return wrapperForResult;
                case "toString":
                    return "fake-delegate";
                default:
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    return null;
            }
        }
    }

    /** A cli whose session's liveness and expiration the test controls. */
    private static final class ControllableCli implements BoundaryCli {
        final AtomicBoolean alive = new AtomicBoolean(true);
        volatile Instant expiration = Instant.now().plus(Duration.ofHours(1));

        @Override
        public LiveSession connect(TargetKey key) {
            return new LiveSession() {
                @Override
                public BoundarySession data() {
                    return new BoundarySession("127.0.0.1", 1, "tcp", expiration, -1, "s", List.of());
                }

                @Override
                public boolean isAlive() {
                    return alive.get();
                }

                @Override
                public void kill() {
                    alive.set(false);
                }
            };
        }
    }

    private static Connection fakeConnection(FakeDelegate handler) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionWrapperTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private static SessionManager manager(ControllableCli cli) {
        return new SessionManager(cli, Duration.ofSeconds(60));
    }

    @Test
    void closeReleasesExactlyOnceAndIsIdempotent() throws Exception {
        FakeDelegate handler = new FakeDelegate();
        try (SessionManager mgr = manager(new ControllableCli())) {
            Handle handle = mgr.acquire(KEY);
            assertEquals(1, mgr.refCount(KEY));

            Connection wrapped = ConnectionWrapper.wrap(fakeConnection(handler), mgr, handle);
            wrapped.close();
            assertEquals(0, mgr.refCount(KEY), "first close releases the ref");

            wrapped.close();
            assertEquals(0, mgr.refCount(KEY), "double close must not double-decrement");
            assertEquals(1, handler.closeCount, "the delegate is closed exactly once");
        }
    }

    @Test
    void isClosedReflectsWrapperState() throws Exception {
        FakeDelegate handler = new FakeDelegate();
        try (SessionManager mgr = manager(new ControllableCli())) {
            Handle handle = mgr.acquire(KEY);
            Connection wrapped = ConnectionWrapper.wrap(fakeConnection(handler), mgr, handle);
            assertFalse(wrapped.isClosed());
            wrapped.close();
            assertTrue(wrapped.isClosed());
        }
    }

    @Test
    void unwrapReturnsProxyForConnectionAndDelegatesOtherwise() throws Exception {
        FakeDelegate handler = new FakeDelegate();
        try (SessionManager mgr = manager(new ControllableCli())) {
            Handle handle = mgr.acquire(KEY);
            Connection wrapped = ConnectionWrapper.wrap(fakeConnection(handler), mgr, handle);

            assertSame(wrapped, wrapped.unwrap(Connection.class));
            assertEquals("UNWRAPPED:Statement", wrapped.unwrap(Statement.class));
        }
    }

    @Test
    void isWrapperForHandlesConnectionExplicitlyAndDelegatesOtherwise() throws Exception {
        FakeDelegate handler = new FakeDelegate();
        handler.wrapperForResult = false;
        try (SessionManager mgr = manager(new ControllableCli())) {
            Handle handle = mgr.acquire(KEY);
            Connection wrapped = ConnectionWrapper.wrap(fakeConnection(handler), mgr, handle);

            assertTrue(wrapped.isWrapperFor(Connection.class), "we ARE a Connection");
            assertFalse(wrapped.isWrapperFor(Statement.class), "delegates to the real connection");
        }
    }

    @Test
    void remapsToSessionEndedWhenTheSessionDied() throws Exception {
        FakeDelegate handler = new FakeDelegate();
        handler.throwOnMethod = "createStatement";
        handler.throwSqlState = "08006";
        ControllableCli cli = new ControllableCli();
        try (SessionManager mgr = manager(cli)) {
            Handle handle = mgr.acquire(KEY);
            Connection wrapped = ConnectionWrapper.wrap(fakeConnection(handler), mgr, handle);

            cli.alive.set(false);   // the Boundary session's proxy has died

            SQLException e = assertThrows(SQLException.class, wrapped::createStatement);
            assertEquals(SqlStates.CONNECTION_FAILURE, e.getSQLState());
            assertTrue(e.getMessage().contains("session"), "message should explain the session ended");
        }
    }

    @Test
    void remapsToSessionEndedWhenTheSessionExpired() throws Exception {
        FakeDelegate handler = new FakeDelegate();
        handler.throwOnMethod = "createStatement";
        handler.throwSqlState = "08006";
        ControllableCli cli = new ControllableCli();
        cli.expiration = Instant.now().minusSeconds(1);   // already expired, process still "alive"
        try (SessionManager mgr = manager(cli)) {
            Handle handle = mgr.acquire(KEY);
            Connection wrapped = ConnectionWrapper.wrap(fakeConnection(handler), mgr, handle);

            SQLException e = assertThrows(SQLException.class, wrapped::createStatement);
            assertEquals(SqlStates.CONNECTION_FAILURE, e.getSQLState());
        }
    }

    @Test
    void doesNotRemapSqlExceptionWhenSessionIsHealthy() throws Exception {
        FakeDelegate handler = new FakeDelegate();
        handler.throwOnMethod = "createStatement";
        handler.throwSqlState = "42601";       // e.g. a syntax error — a legitimate query failure
        try (SessionManager mgr = manager(new ControllableCli())) {
            Handle handle = mgr.acquire(KEY);
            Connection wrapped = ConnectionWrapper.wrap(fakeConnection(handler), mgr, handle);

            SQLException e = assertThrows(SQLException.class, wrapped::createStatement);
            assertEquals("42601", e.getSQLState(), "a healthy session must pass query errors through unchanged");
        }
    }
}
