package dev.isonet.valet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M3 integration tests (§12) — the highest-value tests in the repo.
 *
 * <p>Each runs a real PostgreSQL (Testcontainers) behind a real Boundary session, with the
 * database password living <em>only</em> in the Boundary credential and nowhere in the JDBC
 * URL — so a passing query proves brokering, not just a TCP proxy. Three real JDBC-client
 * shapes are exercised:
 * <ol>
 *   <li>plain {@link DriverManager};</li>
 *   <li>a <b>HikariCP</b> connection pool (concurrent checkouts sharing one session, plus a
 *       cycle that reuses it) — the pool the ref-counting design exists for;</li>
 *   <li>the driver loaded through an <b>isolated {@link URLClassLoader}</b> from the shaded
 *       bundle jar — exactly how a JetBrains IDE or DBeaver loads a driver, which is why Valet
 *       loads PGJDBC by reflection rather than via {@code DriverManager} (§9).</li>
 * </ol>
 *
 * <p>Prerequisites (the tests self-skip when any is missing): a Docker daemon; and an
 * authenticated Boundary session reachable by the spawned CLI — {@code BOUNDARY_ADDR} plus a
 * {@code BOUNDARY_TOKEN} this JVM can inherit. The {@code boundary} binary comes from the
 * {@code downloadBoundary} task via {@code -Dvalet.it.boundary}; the bundle jar from
 * {@code :bundle:shadowJar} via {@code -Dvalet.it.bundle}. CI stands up {@code boundary dev},
 * authenticates, and exports the token before {@code ./gradlew integrationTest}.
 */
class BoundaryIntegrationTest {

    private static final String BOUNDARY = System.getProperty("valet.it.boundary", "boundary");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void brokersThroughDriverManager() throws Exception {
        assumePrerequisites();
        try (PostgreSQLContainer<?> pg = newPostgres()) {
            pg.start();
            String url = provisionBrokeredTarget(pg);
            assertFalse(url.contains(pg.getPassword()), "the password must not appear in the URL");

            try (Connection c = DriverManager.getConnection(url, new Properties());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void brokersThroughAHikariPool() throws Exception {
        assumePrerequisites();
        try (PostgreSQLContainer<?> pg = newPostgres()) {
            pg.start();
            String url = provisionBrokeredTarget(pg);

            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(url);                 // no user/password — Valet brokers them
            cfg.setMaximumPoolSize(4);
            cfg.setConnectionTimeout(20_000);

            try (HikariDataSource ds = new HikariDataSource(cfg)) {
                // More concurrent checkouts than pool slots: connections share ONE Boundary
                // session and the ref counting must stay balanced under contention.
                int tasks = 8;
                ExecutorService pool = Executors.newFixedThreadPool(tasks);
                try {
                    List<Future<Integer>> results = new ArrayList<>();
                    for (int i = 0; i < tasks; i++) {
                        results.add(pool.submit(() -> {
                            try (Connection c = ds.getConnection();
                                 Statement st = c.createStatement();
                                 ResultSet rs = st.executeQuery("SELECT 1")) {
                                rs.next();
                                return rs.getInt(1);
                            }
                        }));
                    }
                    for (Future<Integer> r : results) {
                        assertEquals(1, r.get(30, TimeUnit.SECONDS));
                    }
                } finally {
                    pool.shutdownNow();
                }

                // Reopen after the burst — the session is reused, and the brokered user is in effect.
                try (Connection c = ds.getConnection();
                     Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT current_user")) {
                    assertTrue(rs.next());
                    assertEquals(pg.getUsername(), rs.getString(1), "connected as the brokered user");
                }
            }
        }
    }

    @Test
    void loadsThroughAnIsolatedClassLoaderLikeAnIde() throws Exception {
        assumePrerequisites();
        String bundlePath = System.getProperty("valet.it.bundle");
        assumeTrue(bundlePath != null && new File(bundlePath).isFile(),
                "bundle jar not built (run :bundle:shadowJar)");

        try (PostgreSQLContainer<?> pg = newPostgres()) {
            pg.start();
            String url = provisionBrokeredTarget(pg);

            // Only the bundle jar is visible; the parent is the platform loader, so the app
            // classpath's driver/PGJDBC/Jackson are hidden — the loading model of a JetBrains
            // IDE or DBeaver. If Valet used DriverManager.getDriver for PGJDBC this would fail;
            // it uses Class.forName within this loader, where the shaded PGJDBC lives.
            try (URLClassLoader isolated = new URLClassLoader(
                    new URL[]{new File(bundlePath).toURI().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> driverClass = Class.forName("dev.isonet.valet.ValetDriver", true, isolated);
                Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();

                try (Connection c = driver.connect(url, new Properties());
                     Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    // The connection must unwrap to the (shaded) PGConnection through the wrapper.
                    Class<?> pgConn = Class.forName("org.postgresql.PGConnection", true, isolated);
                    assertTrue(c.isWrapperFor(pgConn), "unwrap(PGConnection) must work in isolation");
                }
            }
        }
    }

    // ---- shared provisioning ----

    private static PostgreSQLContainer<?> newPostgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withUsername("valet_it")
                .withPassword("valet-it-" + UUID.randomUUID())
                .withDatabaseName("appdb");
    }

    /** Provision a Boundary target that brokers {@code pg}'s credential; return the Valet URL. */
    private String provisionBrokeredTarget(PostgreSQLContainer<?> pg) throws Exception {
        Scope scope = firstProjectScope();
        String targetName = "valet-it-" + UUID.randomUUID().toString().substring(0, 8);

        String targetId = idOf(run(Map.of(), "targets", "create", "tcp",
                "-scope-id=" + scope.id(), "-name=" + targetName,
                "-default-port=" + pg.getFirstMappedPort(), "-address=" + pg.getHost(), "-format=json"));

        String storeId = idOf(run(Map.of(), "credential-stores", "create", "static",
                "-scope-id=" + scope.id(), "-format=json"));

        String credentialId = idOf(run(Map.of("VALET_IT_DB_PASSWORD", pg.getPassword()),
                "credentials", "create", "username-password",
                "-credential-store-id=" + storeId,
                "-username=" + pg.getUsername(), "-password=env://VALET_IT_DB_PASSWORD", "-format=json"));

        run(Map.of(), "targets", "add-credential-sources",
                "-id=" + targetId, "-brokered-credential-source=" + credentialId, "-format=json");

        return "jdbc:boundary://"
                + ValetDriver.encodePathSegment(scope.name()) + "/"
                + ValetDriver.encodePathSegment(targetName) + "/"
                + ValetDriver.encodePathSegment(pg.getDatabaseName())
                + "?boundary.cli-path=" + BOUNDARY.replace(" ", "%20");
    }

    private Scope firstProjectScope() throws Exception {
        JsonNode items = JSON.readTree(run(Map.of(), "scopes", "list", "-recursive", "-format=json")).path("items");
        for (JsonNode item : items) {
            if ("project".equals(item.path("type").asText())) {
                return new Scope(item.path("id").asText(), item.path("name").asText());
            }
        }
        throw new IllegalStateException("no project scope found; create one under your org");
    }

    private static void assumePrerequisites() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        assumeTrue(System.getenv("BOUNDARY_ADDR") != null, "BOUNDARY_ADDR is not set");
        assumeTrue(authenticated(), "no authenticated Boundary session (BOUNDARY_TOKEN / keyring)");
    }

    private static boolean authenticated() {
        try {
            return exec(Map.of(), "scopes", "list", "-recursive", "-format=json").exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String idOf(String json) throws IOException {
        return JSON.readTree(json).path("item").path("id").asText();
    }

    private String run(Map<String, String> extraEnv, String... boundaryArgs)
            throws IOException, InterruptedException {
        Result r = exec(extraEnv, boundaryArgs);
        if (r.exitCode != 0) {
            throw new IllegalStateException("boundary " + String.join(" ", boundaryArgs)
                    + " failed:\n" + r.out + "\n" + r.err);
        }
        return r.out;
    }

    private static Result exec(Map<String, String> extraEnv, String... boundaryArgs)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(BOUNDARY);
        cmd.addAll(List.of(boundaryArgs));
        // Do NOT merge stderr into stdout: BOUNDARY_TOKEN triggers a deprecation notice on
        // stderr that would otherwise corrupt the -format=json output we parse.
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(extraEnv);

        Process p = pb.start();
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("boundary " + String.join(" ", boundaryArgs) + " timed out");
        }
        return new Result(p.exitValue(), out, err.join());
    }

    private record Scope(String id, String name) {}

    private record Result(int exitCode, String out, String err) {}
}
