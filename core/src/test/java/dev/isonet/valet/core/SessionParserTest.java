package dev.isonet.valet.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionParserTest {

    private static InputStream fixture() {
        InputStream in = SessionParserTest.class.getResourceAsStream("/fixtures/session.json");
        assertNotNull(in, "fixtures/session.json must be on the test classpath");
        return in;
    }

    private static InputStream of(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesTheCommittedFixture() throws IOException {
        BoundarySession s = SessionParser.readOne(fixture());

        assertEquals("127.0.0.1", s.address());
        assertEquals(63102, s.port());
        assertEquals("tcp", s.protocol());
        assertEquals(Instant.parse("2026-07-15T18:41:33.920001Z"), s.expiration());
        assertEquals(-1, s.connectionLimit());
        assertEquals("s_EXAMPLE00001", s.sessionId());

        assertEquals(1, s.credentials().size());
        BrokeredCredential c = s.credentials().get(0);
        assertEquals("example-user", c.username());
        assertEquals("example-password", c.password());
        assertEquals("example-credential", c.sourceName());
        assertEquals("username_password", c.credentialType());
    }

    @Test
    void readsMicrosecondRfc3339ExpirationWithoutACustomFormatter() throws IOException {
        BoundarySession s = SessionParser.readOne(fixture());
        // microsecond precision survives
        assertEquals(920_001_000, s.expiration().getNano());
    }

    @Test
    void prefersCredentialOverSecretDecoded() throws IOException {
        String json = "{\"address\":\"127.0.0.1\",\"port\":1,\"session_id\":\"s_1\","
                + "\"connection_limit\":-1,\"credentials\":[{"
                + "\"credential_source\":{\"name\":\"c\",\"credential_type\":\"username_password\"},"
                + "\"secret\":{\"decoded\":{\"username\":\"from-decoded\",\"password\":\"decoded-pw\"}},"
                + "\"credential\":{\"username\":\"from-credential\",\"password\":\"credential-pw\"}}]}";
        BoundarySession s = SessionParser.readOne(of(json));
        assertEquals("from-credential", s.credentials().get(0).username());
        assertEquals("credential-pw", s.credentials().get(0).password());
    }

    @Test
    void fallsBackToSecretDecodedWhenCredentialAbsent() throws IOException {
        String json = "{\"address\":\"127.0.0.1\",\"port\":1,\"session_id\":\"s_1\","
                + "\"connection_limit\":-1,\"credentials\":[{"
                + "\"credential_source\":{\"name\":\"c\",\"credential_type\":\"username_password\"},"
                + "\"secret\":{\"decoded\":{\"username\":\"decoded-user\",\"password\":\"decoded-pw\"}}}]}";
        BoundarySession s = SessionParser.readOne(of(json));
        assertEquals("decoded-user", s.credentials().get(0).username());
        assertEquals("decoded-pw", s.credentials().get(0).password());
    }

    @Test
    void toleratesUnknownProperties() throws IOException {
        String json = "{\"address\":\"127.0.0.1\",\"port\":1,\"session_id\":\"s_1\","
                + "\"connection_limit\":-1,\"brand_new_field\":{\"nested\":true},\"credentials\":[]}";
        BoundarySession s = SessionParser.readOne(of(json));
        assertEquals("s_1", s.sessionId());
    }

    @Test
    void stopsAtTheFirstValueAndIgnoresTrailingProxyOutput() throws IOException {
        // -format=json may be followed by non-JSON as the proxy runs; readOne must stop cleanly.
        String json = "{\"address\":\"127.0.0.1\",\"port\":7,\"session_id\":\"s_1\",\"connection_limit\":-1,"
                + "\"credentials\":[]}\nsome trailing proxy log line\nmore output\n";
        BoundarySession s = SessionParser.readOne(of(json));
        assertEquals(7, s.port());
    }

    @Test
    void toStringRedactsPasswords() throws IOException {
        BoundarySession s = SessionParser.readOne(fixture());
        String text = s.toString();
        assertFalse(text.contains("example-password"), "password must not appear in toString");
        assertTrue(text.contains("<redacted>"));
    }

    @Test
    void selectThrowsWhenMultipleMatchAndNoNameGiven() throws IOException {
        BoundarySession s = twoUsernamePasswordCreds();
        ValetException e = assertThrows(ValetException.class,
                () -> s.selectUsernamePassword(Optional.empty(), "target 'x'"));
        assertEquals(SqlStates.UNABLE_TO_CONNECT, e.getSQLState());
        assertTrue(e.getMessage().contains("credential-name"));
    }

    @Test
    void selectDisambiguatesByCredentialName() throws Exception {
        BoundarySession s = twoUsernamePasswordCreds();
        BrokeredCredential c = s.selectUsernamePassword(Optional.of("cred-b"), "target 'x'");
        assertEquals("user-b", c.username());
    }

    @Test
    void selectThrowsWhenNoUsernamePasswordCredential() {
        BoundarySession s = new BoundarySession("127.0.0.1", 1, "tcp", Instant.now(), -1, "s",
                List.of(new BrokeredCredential(null, null, "ssh", "ssh_private_key")));
        ValetException e = assertThrows(ValetException.class,
                () -> s.selectUsernamePassword(Optional.empty(), "target 'x'"));
        assertEquals(SqlStates.UNABLE_TO_CONNECT, e.getSQLState());
    }

    private static BoundarySession twoUsernamePasswordCreds() {
        return new BoundarySession("127.0.0.1", 1, "tcp", Instant.now(), -1, "s", List.of(
                new BrokeredCredential("user-a", "pw-a", "cred-a", "username_password"),
                new BrokeredCredential("user-b", "pw-b", "cred-b", "username_password")));
    }
}
