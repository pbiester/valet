package dev.isonet.valet;

import dev.isonet.valet.core.SqlStates;
import org.junit.jupiter.api.Test;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValetDriverTest {

    private final ValetDriver driver = new ValetDriver();

    @Test
    void acceptsBoundaryUrls() {
        assertTrue(driver.acceptsURL("jdbc:boundary://s/t/d"));
    }

    @Test
    void rejectsOtherUrls() {
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/db"));
        assertFalse(driver.acceptsURL(null));
    }

    @Test
    void connectReturnsNullForNonBoundaryUrl() throws SQLException {
        // JDBC contract: a driver returns null for URLs it does not handle.
        assertNull(driver.connect("jdbc:mysql://localhost/db", null));
    }

    @Test
    void connectOnMalformedUrlThrowsInvalidUrl() {
        // Parsing fails before any CLI discovery, so this is environment-independent.
        SQLException e = assertThrows(SQLException.class,
                () -> driver.connect("jdbc:boundary://scope/target", null));
        assertEquals(SqlStates.INVALID_URL, e.getSQLState());
    }

    @Test
    void registersWithDriverManager() throws SQLException {
        // Referencing ValetDriver triggers its static registration block.
        Driver found = DriverManager.getDriver("jdbc:boundary://s/t/d");
        assertInstanceOf(ValetDriver.class, found);
    }

    @Test
    void reportsBoundaryPropertyInfo() {
        assertEquals(6, driver.getPropertyInfo("jdbc:boundary://s/t/d", null).length);
        assertEquals("boundary.addr", driver.getPropertyInfo("jdbc:boundary://s/t/d", null)[0].name);
    }

    @Test
    void isNotJdbcCompliant() {
        assertFalse(driver.jdbcCompliant());
    }

    @Test
    void encodesPathSegmentsForTheDelegateUrl() {
        assertEquals("simple_db", ValetDriver.encodePathSegment("simple_db"));
        assertEquals("my%20db", ValetDriver.encodePathSegment("my db"));
        assertEquals("a%2Fb", ValetDriver.encodePathSegment("a/b"));
        assertEquals("a%2Bb", ValetDriver.encodePathSegment("a+b"));
    }
}
