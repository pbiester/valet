package dev.isonet.valet.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ref-counted cache of Boundary sessions, shared across concurrent connections to the same
 * target (§7).
 *
 * <p>The naive version races teardown against acquire. The fix: hold a <em>lease</em> in the
 * map, not a session, so the map's bin lock is never held across a subprocess spawn. All the
 * subtlety lives in three rules:
 * <ul>
 *   <li>Never {@code join()} inside {@code compute()} — the lambda runs under the bin lock;
 *       blocking on a subprocess spawn there deadlocks every target that hashes to the same bin.
 *       The {@code join()} happens at the call site, outside the lambda.</li>
 *   <li>{@link Lease#isDoomed} must not block — it inspects completion state, never {@code get()}.</li>
 *   <li>Do not kill at zero refs. Keep the lease for an idle grace period; a daemon reaper
 *       evicts leases whose {@code refs == 0} and whose last release is older than the idle
 *       timeout. Otherwise a pool that closes and reopens spawns a fresh session per cycle.</li>
 * </ul>
 *
 * <p>Ten parallel {@link #acquire} calls for one target produce exactly one subprocess — that
 * is the {@code compute} + {@link CompletableFuture} combination.
 */
public final class SessionManager implements AutoCloseable {

    /** Retire a session this long before its hard expiration, to avoid mid-connection death. */
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(60);

    private final ConcurrentHashMap<TargetKey, Lease> sessions = new ConcurrentHashMap<>();
    private final BoundaryCli cli;
    private final Duration idleTimeout;
    private final ExecutorService connectExecutor;
    private final ScheduledExecutorService reaper;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread shutdownHook;

    public SessionManager(BoundaryCli cli, Duration idleTimeout) {
        this(cli, idleTimeout, Duration.ofSeconds(10), true);
    }

    /** Full constructor for tests: control the reaper tick and whether a JVM shutdown hook is added. */
    SessionManager(BoundaryCli cli, Duration idleTimeout, Duration reaperTick, boolean installShutdownHook) {
        this.cli = cli;
        this.idleTimeout = idleTimeout;
        this.connectExecutor = Executors.newCachedThreadPool(daemonFactory("valet-connect"));
        this.reaper = Executors.newSingleThreadScheduledExecutor(daemonFactory("valet-reaper"));
        this.reaper.scheduleWithFixedDelay(this::reapQuietly,
                reaperTick.toMillis(), reaperTick.toMillis(), TimeUnit.MILLISECONDS);
        if (installShutdownHook) {
            this.shutdownHook = new Thread(this::killAll, "valet-shutdown");
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        } else {
            this.shutdownHook = null;
        }
    }

    /**
     * Acquire the shared session for {@code key}, incrementing its ref count. Blocks (outside
     * the map lock) until the session is established. On failure the ref taken here is undone.
     *
     * <p>Returns a {@link Handle} bound to the <em>specific</em> lease whose count was
     * incremented, so {@link #release(Handle)} decrements exactly that lease — never a
     * different one that may have replaced it after a doom-eviction.
     */
    public Handle acquire(TargetKey key) throws ValetException {
        if (closed.get()) {
            throw new ValetException("SessionManager is closed.", SqlStates.UNABLE_TO_CONNECT);
        }
        Lease lease = takeLease(key);
        try {
            LiveSession session = lease.future.join();   // .join() OUTSIDE compute() — never inside.
            return new Handle(key, lease, session);
        } catch (CompletionException e) {
            release(key, lease);                         // undo our ref; the doomed lease is reaped
            throw unwrap(e, key);
        }
    }

    private Lease takeLease(TargetKey key) {
        Instant now = Instant.now();
        return sessions.compute(key, (k, current) -> {
            if (current != null && !current.isDoomed(now)) {
                current.refs++;
                return current;
            }
            if (current != null) {
                current.killAsync();                  // doomed — replace it
            }
            CompletableFuture<LiveSession> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return cli.connect(k);
                } catch (ValetException e) {
                    throw new CompletionException(e);
                }
            }, connectExecutor);
            Lease fresh = new Lease(future);
            fresh.refs = 1;
            return fresh;
        });
    }

    /**
     * Release the reference held by {@code handle}. At zero refs the lease is kept (its
     * last-release stamped) so a pool that reopens reuses it; the reaper evicts it after the
     * idle grace. Callers must release each handle at most once.
     */
    public void release(Handle handle) {
        release(handle.key, handle.lease);
    }

    /**
     * Decrement {@code lease}'s ref count — but only if it is still the lease mapped at
     * {@code key}. If a doom-eviction replaced it in the meantime, that old lease's process
     * was already killed and the connection is dead, so we must NOT decrement whatever lease
     * now sits at the key: doing so would corrupt the new session's count and let the reaper
     * evict a session that fresh connections are actively using.
     */
    private void release(TargetKey key, Lease lease) {
        sessions.computeIfPresent(key, (k, current) -> {
            if (current == lease && --current.refs <= 0) {
                current.lastReleaseNanos = System.nanoTime();
            }
            return current;
        });
    }

    /** Evict leases that are idle-expired or doomed and no longer referenced. */
    void reap() {
        long nowNanos = System.nanoTime();
        Instant nowInstant = Instant.now();
        long idleNanos = idleTimeout.toNanos();
        for (TargetKey key : sessions.keySet()) {
            sessions.computeIfPresent(key, (k, lease) -> {
                if (lease.refs > 0) {
                    return lease;                     // in use — never evict from under a connection
                }
                boolean idleExpired = nowNanos - lease.lastReleaseNanos >= idleNanos;
                if (idleExpired || lease.isDoomed(nowInstant)) {
                    lease.killAsync();
                    return null;                      // evict
                }
                return lease;
            });
        }
    }

    private void reapQuietly() {
        try {
            reap();
        } catch (RuntimeException ignored) {
            // A reaper tick must never die; a transient error just means we try again next tick.
        }
    }

    /** Current number of cached leases — for diagnostics and tests. */
    public int leaseCount() {
        return sessions.size();
    }

    /** Ref count for a key, or -1 if absent — for diagnostics and tests. */
    public int refCount(TargetKey key) {
        Lease lease = sessions.get(key);
        return lease == null ? -1 : lease.refs;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // Already shutting down.
            }
        }
        killAll();
        reaper.shutdownNow();
        connectExecutor.shutdownNow();
    }

    private void killAll() {
        for (Lease lease : sessions.values()) {
            lease.killAsync();
        }
        sessions.clear();
    }

    private static ValetException unwrap(CompletionException e, TargetKey key) {
        Throwable cause = e.getCause();
        if (cause instanceof ValetException valet) {
            return valet;
        }
        return new ValetException("Failed to start a Boundary session for " + key.describe()
                + ": " + (cause == null ? e : cause).getMessage() + ".",
                SqlStates.UNABLE_TO_CONNECT, cause == null ? e : cause);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicLong seq = new AtomicLong();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * A held reference to a shared session. Obtain one from {@link #acquire} and return it
     * to {@link #release(Handle)} exactly once. It carries the specific {@link Lease} it
     * incremented so release cannot be misattributed to a replacement lease.
     */
    public static final class Handle {
        private final TargetKey key;
        private final Lease lease;
        private final LiveSession session;

        Handle(TargetKey key, Lease lease, LiveSession session) {
            this.key = key;
            this.lease = lease;
            this.session = session;
        }

        /** The established session backing this handle. */
        public LiveSession session() {
            return session;
        }

        /** The target this handle is for (for diagnostics / error messages). */
        public TargetKey targetKey() {
            return key;
        }
    }

    /**
     * A pending or established session with its reference count. {@code refs} is mutated only
     * inside {@code compute}/{@code computeIfPresent} lambdas, i.e. under the map's per-bin
     * lock — so it needs no additional synchronization.
     */
    static final class Lease {
        final CompletableFuture<LiveSession> future;
        int refs;                                     // guarded by the map's bin lock
        volatile long lastReleaseNanos;

        Lease(CompletableFuture<LiveSession> future) {
            this.future = future;
        }

        /** Non-blocking (§7): never calls {@code get()}. */
        boolean isDoomed(Instant now) {
            if (future.isCompletedExceptionally()) {
                return true;
            }
            LiveSession session = future.getNow(null);     // null if still connecting — not doomed
            if (session == null) {
                return false;
            }
            if (!session.isAlive()) {
                return true;
            }
            return session.data().isExpiredOrExpiringWithin(EXPIRY_GRACE, now);
        }

        /** Kill the backing session once it exists; a no-op if the connect failed. */
        void killAsync() {
            future.whenComplete((session, error) -> {
                if (session != null) {
                    session.kill();
                }
            });
        }
    }
}
