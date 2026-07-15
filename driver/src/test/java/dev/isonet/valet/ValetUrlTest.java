package dev.isonet.valet;

import dev.isonet.valet.core.SqlStates;
import dev.isonet.valet.core.ValetException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValetUrlTest {

    private static ValetException parseFails(String url) {
        ValetException e = assertThrows(ValetException.class, () -> ValetUrl.parse(url));
        assertEquals(SqlStates.INVALID_URL, e.getSQLState());
        return e;
    }

    @Test
    void parsesThreeSegments() throws Exception {
        ValetUrl u = ValetUrl.parse("jdbc:boundary://project-alpha/orders-db/appdb");
        assertEquals("project-alpha", u.scopeName());
        assertEquals("orders-db", u.targetName());
        assertEquals("appdb", u.database());
    }

    @Test
    void appliesDefaultsWithNoParams() throws Exception {
        ValetUrl u = ValetUrl.parse("jdbc:boundary://s/t/d");
        assertEquals(Duration.ofSeconds(30), u.connectTimeout());
        assertEquals(Duration.ofSeconds(60), u.idleTimeout());
        assertTrue(u.brokeredCredentials());
        assertTrue(u.credentialName().isEmpty());
        assertEquals(null, u.controllerAddr());
        assertEquals(null, u.cliPath());
        assertEquals("", u.passThroughQuery());
    }

    @Test
    void rejectsTwoSegments() {
        parseFails("jdbc:boundary://scope/target");
    }

    @Test
    void rejectsFourSegments() {
        parseFails("jdbc:boundary://scope/target/db/extra");
    }

    @Test
    void rejectsTrailingSlashAsEmptyDatabase() {
        ValetException e = parseFails("jdbc:boundary://scope/target/");
        assertTrue(e.getMessage().contains("database"));
    }

    @Test
    void rejectsEmptyInteriorSegment() {
        parseFails("jdbc:boundary://scope//db");
    }

    @Test
    void rejectsEmptyScope() {
        parseFails("jdbc:boundary:///target/db");
    }

    @Test
    void rejectsMissingDoubleSlash() {
        parseFails("jdbc:boundary:scope/target/db");
    }

    @Test
    void rejectsNonBoundaryScheme() {
        parseFails("jdbc:postgresql://localhost/db");
    }

    @Test
    void percentDecodesEachSegmentIndividually() throws Exception {
        // A scope name containing '/' arrives as %2F and must survive as a single segment.
        ValetUrl u = ValetUrl.parse("jdbc:boundary://my%2Fscope/my%20target/db%2Dname");
        assertEquals("my/scope", u.scopeName());
        assertEquals("my target", u.targetName());
        assertEquals("db-name", u.database());
    }

    @Test
    void plusInPathSegmentStaysLiteral() throws Exception {
        // Unlike a query string, '+' in a path segment is a literal '+', not a space.
        ValetUrl u = ValetUrl.parse("jdbc:boundary://s/t/a+b");
        assertEquals("a+b", u.database());
    }

    @Test
    void rejectsTruncatedPercentEncoding() {
        parseFails("jdbc:boundary://s/t/db%2");
    }

    @Test
    void separatesBoundaryParamsFromPassThrough() throws Exception {
        ValetUrl u = ValetUrl.parse(
                "jdbc:boundary://s/t/d?ssl=true&boundary.addr=https://boundary.example&ApplicationName=foo");
        assertEquals("https://boundary.example", u.controllerAddr());
        assertEquals("?ssl=true&ApplicationName=foo", u.passThroughQuery());
    }

    @Test
    void parsesAllBoundaryParams() throws Exception {
        ValetUrl u = ValetUrl.parse("jdbc:boundary://s/t/d"
                + "?boundary.addr=https://c"
                + "&boundary.cli-path=/opt/boundary"
                + "&boundary.connect-timeout=5s"
                + "&boundary.idle-timeout=2m"
                + "&boundary.credential-name=primary"
                + "&boundary.brokered-credentials=false");
        assertEquals("https://c", u.controllerAddr());
        assertEquals("/opt/boundary", u.cliPath());
        assertEquals(Duration.ofSeconds(5), u.connectTimeout());
        assertEquals(Duration.ofMinutes(2), u.idleTimeout());
        assertEquals("primary", u.credentialName().orElseThrow());
        assertFalse(u.brokeredCredentials());
        assertEquals("", u.passThroughQuery());
    }

    @Test
    void rejectsUnknownBoundaryParam() {
        ValetException e = parseFails("jdbc:boundary://s/t/d?boundary.addres=typo");
        assertTrue(e.getMessage().contains("unknown parameter"));
    }

    @Test
    void rejectsInvalidDuration() {
        parseFails("jdbc:boundary://s/t/d?boundary.connect-timeout=soon");
    }

    @Test
    void rejectsInvalidBoolean() {
        parseFails("jdbc:boundary://s/t/d?boundary.brokered-credentials=maybe");
    }

    @Test
    void parsesBareSecondsDuration() throws Exception {
        ValetUrl u = ValetUrl.parse("jdbc:boundary://s/t/d?boundary.connect-timeout=45");
        assertEquals(Duration.ofSeconds(45), u.connectTimeout());
    }
}
