package dev.isonet.valet.core;

import java.sql.SQLException;

/**
 * A Valet failure that carries a JDBC {@code SQLState} so clients (IDEs, pools, apps)
 * can render an actionable error rather than a bare stack trace (§10).
 *
 * <p>This extends {@link SQLException} — which lives in the JDK's {@code java.sql} module,
 * not in any JDBC driver — so {@code core} still has no dependency on a database driver.
 */
public class ValetException extends SQLException {

    private static final long serialVersionUID = 1L;

    public ValetException(String reason, String sqlState) {
        super(reason, sqlState);
    }

    public ValetException(String reason, String sqlState, Throwable cause) {
        super(reason, sqlState, cause);
    }
}
