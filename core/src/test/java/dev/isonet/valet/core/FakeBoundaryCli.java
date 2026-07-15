package dev.isonet.valet.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link BoundaryCli} that drives {@link SessionManager} with no subprocess and no
 * controller (§12). {@link #connectCount} lets tests assert that N concurrent acquires
 * produce exactly one connect.
 */
public final class FakeBoundaryCli implements BoundaryCli {

    public final AtomicInteger connectCount = new AtomicInteger();
    public volatile long connectDelayMillis = 0;
    public volatile boolean failNext = false;

    public final ConcurrentHashMap<TargetKey, FakeLiveSession> sessions = new ConcurrentHashMap<>();

    @Override
    public LiveSession connect(TargetKey key) throws ValetException {
        connectCount.incrementAndGet();
        long delay = connectDelayMillis;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failNext) {
            throw new ValetException("simulated connect failure for " + key.describe(),
                    SqlStates.UNABLE_TO_CONNECT);
        }
        FakeLiveSession session = new FakeLiveSession(Instant.now().plus(Duration.ofHours(1)));
        sessions.put(key, session);
        return session;
    }

    /** A controllable {@link LiveSession}: flip {@link #alive} to simulate the proxy dying. */
    public static final class FakeLiveSession implements LiveSession {
        private final BoundarySession data;
        public final AtomicBoolean alive = new AtomicBoolean(true);
        public final AtomicInteger kills = new AtomicInteger();

        public FakeLiveSession(Instant expiration) {
            this.data = new BoundarySession("127.0.0.1", 55555, "tcp", expiration, -1, "s_fake",
                    List.of(new BrokeredCredential("fake-user", "fake-pass", "cred", "username_password")));
        }

        @Override
        public BoundarySession data() {
            return data;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public void kill() {
            alive.set(false);
            kills.incrementAndGet();
        }
    }
}
