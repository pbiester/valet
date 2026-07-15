package dev.isonet.valet.core;

import dev.isonet.valet.core.FakeBoundaryCli.FakeLiveSession;
import dev.isonet.valet.core.SessionManager.Handle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    private static final Duration NEVER = Duration.ofHours(1);

    private static TargetKey key() {
        return new TargetKey("https://boundary.example", "project-alpha", "orders-db");
    }

    private static SessionManager manager(FakeBoundaryCli cli, Duration idle) {
        // Large reaper tick so it never fires on its own; tests call reap() deterministically.
        return new SessionManager(cli, idle, NEVER, false);
    }

    @Test
    void fiftyConcurrentAcquiresProduceExactlyOneConnect() throws Exception {
        FakeBoundaryCli cli = new FakeBoundaryCli();
        cli.connectDelayMillis = 200;      // keep the single connect in-flight while all threads pile in
        try (SessionManager mgr = manager(cli, Duration.ofSeconds(60))) {
            TargetKey key = key();
            int n = 50;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            List<Handle> acquired = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<Throwable> error = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                for (int i = 0; i < n; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            acquired.add(mgr.acquire(key));
                        } catch (Throwable t) {
                            error.set(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "all acquires should complete");
            } finally {
                pool.shutdownNow();
            }

            assertNull(error.get(), "no acquire should fail");
            assertEquals(1, cli.connectCount.get(), "exactly one subprocess for N concurrent acquires");
            assertEquals(n, acquired.size());
            assertEquals(n, mgr.refCount(key));

            for (Handle handle : acquired) {
                mgr.release(handle);
            }
            assertEquals(0, mgr.refCount(key), "every release must be accounted for");
        }
    }

    @Test
    void reaperEvictsIdleZeroRefLeasesAndKillsTheSession() throws Exception {
        FakeBoundaryCli cli = new FakeBoundaryCli();
        try (SessionManager mgr = manager(cli, Duration.ofMillis(20))) {
            TargetKey key = key();
            Handle handle = mgr.acquire(key);
            FakeLiveSession fake = cli.sessions.get(key);
            mgr.release(handle);
            assertEquals(0, mgr.refCount(key));

            Thread.sleep(50);              // exceed the idle timeout
            mgr.reap();

            assertEquals(0, mgr.leaseCount(), "idle lease evicted");
            assertEquals(1, fake.kills.get(), "evicted session's process killed");
        }
    }

    @Test
    void reaperKeepsRecentlyReleasedLeasesForReuse() throws Exception {
        FakeBoundaryCli cli = new FakeBoundaryCli();
        try (SessionManager mgr = manager(cli, Duration.ofSeconds(60))) {
            TargetKey key = key();
            mgr.release(mgr.acquire(key));

            mgr.reap();                    // idle grace not elapsed
            assertEquals(1, mgr.leaseCount(), "lease kept during idle grace");

            mgr.acquire(key);              // a pool reopening reuses it
            assertEquals(1, cli.connectCount.get(), "no new subprocess on reuse");
        }
    }

    @Test
    void doomedSessionIsReplacedOnNextAcquire() throws Exception {
        FakeBoundaryCli cli = new FakeBoundaryCli();
        try (SessionManager mgr = manager(cli, Duration.ofSeconds(60))) {
            TargetKey key = key();
            Handle first = mgr.acquire(key);
            cli.sessions.get(key).alive.set(false);   // the proxy died under us
            mgr.release(first);

            Handle replacement = mgr.acquire(key);
            assertEquals(2, cli.connectCount.get(), "a doomed session is replaced");
            assertTrue(replacement.session().isAlive());
        }
    }

    @Test
    void releasingADoomReplacedHandleDoesNotDecrementTheNewLease() throws Exception {
        FakeBoundaryCli cli = new FakeBoundaryCli();
        try (SessionManager mgr = manager(cli, Duration.ofSeconds(60))) {
            TargetKey key = key();
            Handle a = mgr.acquire(key);              // lease L1, refs=1
            cli.sessions.get(key).alive.set(false);   // L1's session dies
            Handle b = mgr.acquire(key);              // L1 is doomed -> replaced by L2, refs=1
            assertEquals(2, cli.connectCount.get());
            assertEquals(1, mgr.refCount(key), "the fresh lease starts at one ref");

            // Releasing the OLD (doom-replaced) handle must not touch L2 — the bug was that
            // release(key) decremented whatever lease was currently mapped, corrupting L2.
            mgr.release(a);
            assertEquals(1, mgr.refCount(key),
                    "releasing a doom-replaced handle must not decrement the replacement lease");

            mgr.release(b);
            assertEquals(0, mgr.refCount(key));
        }
    }

    @Test
    void failedAcquireDoesNotLeakARefAndRecovers() throws Exception {
        FakeBoundaryCli cli = new FakeBoundaryCli();
        cli.failNext = true;
        try (SessionManager mgr = manager(cli, Duration.ofSeconds(60))) {
            TargetKey key = key();
            assertThrows(ValetException.class, () -> mgr.acquire(key));
            assertTrue(mgr.refCount(key) <= 0, "the ref taken on a failed connect is undone");

            cli.failNext = false;
            Handle handle = mgr.acquire(key);
            assertTrue(handle.session().isAlive());
            assertEquals(2, cli.connectCount.get());
        }
    }

    @Test
    void closeKillsEverySessionAndClears() throws Exception {
        FakeBoundaryCli cli = new FakeBoundaryCli();
        SessionManager mgr = manager(cli, Duration.ofSeconds(60));
        TargetKey key = key();
        mgr.acquire(key);
        FakeLiveSession fake = cli.sessions.get(key);

        mgr.close();

        assertEquals(1, fake.kills.get());
        assertEquals(0, mgr.leaseCount());
    }
}
