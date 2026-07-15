package dev.isonet.valet;

import dev.isonet.valet.core.SqlStates;
import dev.isonet.valet.core.ValetException;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses and validates a {@code jdbc:boundary://} URL (§4).
 *
 * <pre>
 *   jdbc:boundary://&lt;scope-name&gt;/&lt;target-name&gt;/&lt;database&gt;[?params]
 * </pre>
 *
 * Exactly three path segments, all required and all non-empty. This is hand-parsed on
 * purpose: {@link java.net.URI} would choke on characters Boundary permits in names, and
 * each segment is percent-decoded individually <em>after</em> splitting so a name
 * containing {@code /} (arriving as {@code %2F}) survives.
 *
 * <p>Query parameters prefixed {@code boundary.} are consumed here; every other parameter
 * is preserved verbatim and forwarded to PGJDBC.
 */
public final class ValetUrl {

    public static final String SCHEME_PREFIX = "jdbc:boundary:";
    static final String GRAMMAR = "jdbc:boundary://<scope-name>/<target-name>/<database>[?params]";

    private static final String AUTHORITY_PREFIX = "jdbc:boundary://";
    private static final String[] SEGMENT_NAMES = {"scope-name", "target-name", "database"};

    private static final String P_ADDR = "boundary.addr";
    private static final String P_CLI_PATH = "boundary.cli-path";
    private static final String P_CONNECT_TIMEOUT = "boundary.connect-timeout";
    private static final String P_IDLE_TIMEOUT = "boundary.idle-timeout";
    private static final String P_CREDENTIAL_NAME = "boundary.credential-name";
    private static final String P_BROKERED = "boundary.brokered-credentials";
    private static final String P_TARGET_ID = "boundary.target-id";

    private static final Set<String> KNOWN_PARAMS = Set.of(
            P_ADDR, P_CLI_PATH, P_CONNECT_TIMEOUT, P_IDLE_TIMEOUT, P_CREDENTIAL_NAME, P_BROKERED, P_TARGET_ID);

    private final String scopeName;
    private final String targetName;
    private final String database;
    private final String controllerAddr;      // nullable → $BOUNDARY_ADDR
    private final String cliPath;              // nullable → discovery
    private final Duration connectTimeout;
    private final Duration idleTimeout;
    private final Optional<String> credentialName;
    private final boolean brokeredCredentials;
    private final Optional<String> targetId;   // boundary.target-id: pin the target, skip name resolution
    private final String passThroughQuery;     // "" or "?a=b&c=d", verbatim

    private ValetUrl(String scopeName, String targetName, String database, String controllerAddr,
                     String cliPath, Duration connectTimeout, Duration idleTimeout,
                     Optional<String> credentialName, boolean brokeredCredentials,
                     Optional<String> targetId, String passThroughQuery) {
        this.scopeName = scopeName;
        this.targetName = targetName;
        this.database = database;
        this.controllerAddr = controllerAddr;
        this.cliPath = cliPath;
        this.connectTimeout = connectTimeout;
        this.idleTimeout = idleTimeout;
        this.credentialName = credentialName;
        this.brokeredCredentials = brokeredCredentials;
        this.targetId = targetId;
        this.passThroughQuery = passThroughQuery;
    }

    public static ValetUrl parse(String url) throws ValetException {
        if (url == null || !url.startsWith(SCHEME_PREFIX)) {
            throw invalid("not a jdbc:boundary URL", url);
        }
        if (!url.startsWith(AUTHORITY_PREFIX)) {
            throw invalid("missing '//' after the scheme", url);
        }

        String rest = url.substring(AUTHORITY_PREFIX.length());
        String pathPart;
        String queryPart;
        int q = rest.indexOf('?');
        if (q >= 0) {
            pathPart = rest.substring(0, q);
            queryPart = rest.substring(q + 1);
        } else {
            pathPart = rest;
            queryPart = "";
        }

        // -1 keeps trailing empties, so "scope/target/" is 3 segments with an empty last one.
        String[] raw = pathPart.split("/", -1);
        if (raw.length != 3) {
            throw invalid("expected exactly 3 path segments <scope-name>/<target-name>/<database>, found "
                    + raw.length, url);
        }
        for (int i = 0; i < 3; i++) {
            if (raw[i].isEmpty()) {
                throw invalid("empty " + SEGMENT_NAMES[i] + " segment (a trailing slash is not a "
                        + "blank database)", url);
            }
        }

        String scope = decodeSegment(raw[0], url);
        String target = decodeSegment(raw[1], url);
        String database = decodeSegment(raw[2], url);

        Map<String, String> boundaryParams = new LinkedHashMap<>();
        StringBuilder passThrough = new StringBuilder();
        if (!queryPart.isEmpty()) {
            for (String token : queryPart.split("&")) {
                if (token.isEmpty()) {
                    continue;
                }
                int eq = token.indexOf('=');
                String rawKey = eq >= 0 ? token.substring(0, eq) : token;
                String rawVal = eq >= 0 ? token.substring(eq + 1) : "";
                String key = formDecode(rawKey);
                if (key.startsWith("boundary.")) {
                    if (!KNOWN_PARAMS.contains(key)) {
                        throw invalid("unknown parameter '" + key + "'; known boundary parameters are "
                                + KNOWN_PARAMS, url);
                    }
                    boundaryParams.put(key, formDecode(rawVal));
                } else {
                    if (passThrough.length() > 0) {
                        passThrough.append('&');
                    }
                    passThrough.append(token);       // forward verbatim to PGJDBC
                }
            }
        }

        String addr = trimToNull(boundaryParams.get(P_ADDR));
        String cliPath = trimToNull(boundaryParams.get(P_CLI_PATH));
        Duration connectTimeout = parseDuration(boundaryParams.getOrDefault(P_CONNECT_TIMEOUT, "30s"),
                P_CONNECT_TIMEOUT, url);
        Duration idleTimeout = parseDuration(boundaryParams.getOrDefault(P_IDLE_TIMEOUT, "60s"),
                P_IDLE_TIMEOUT, url);
        Optional<String> credentialName = Optional.ofNullable(trimToNull(boundaryParams.get(P_CREDENTIAL_NAME)));
        boolean brokered = parseBool(boundaryParams.getOrDefault(P_BROKERED, "true"), P_BROKERED, url);
        Optional<String> targetId = Optional.ofNullable(trimToNull(boundaryParams.get(P_TARGET_ID)));

        String passThroughQuery = passThrough.length() > 0 ? "?" + passThrough : "";

        return new ValetUrl(scope, target, database, addr, cliPath, connectTimeout, idleTimeout,
                credentialName, brokered, targetId, passThroughQuery);
    }

    public String scopeName() {
        return scopeName;
    }

    public String targetName() {
        return targetName;
    }

    public String database() {
        return database;
    }

    public String controllerAddr() {
        return controllerAddr;
    }

    public String cliPath() {
        return cliPath;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration idleTimeout() {
        return idleTimeout;
    }

    public Optional<String> credentialName() {
        return credentialName;
    }

    public boolean brokeredCredentials() {
        return brokeredCredentials;
    }

    /**
     * The {@code boundary.target-id} (a {@code ttcp_…} id), if given. When present the target
     * is resolved by id (bypassing scope/target-name resolution); the scope and target URL
     * segments are then ignored for resolution.
     */
    public Optional<String> targetId() {
        return targetId;
    }

    /** The non-boundary parameters, ready to append to the delegate URL ({@code ""} or {@code "?…"}). */
    public String passThroughQuery() {
        return passThroughQuery;
    }

    // ---- decoding helpers ----

    /**
     * Strict per-segment percent-decoding: {@code %XX} → byte (UTF-8); a literal {@code +}
     * stays a {@code +} (unlike form decoding), because this is a path segment, not a query.
     */
    static String decodeSegment(String segment, String url) throws ValetException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%') {
                if (i + 2 >= segment.length()) {
                    throw invalid("truncated percent-encoding in segment '" + segment + "'", url);
                }
                int hi = hexValue(segment.charAt(i + 1));
                int lo = hexValue(segment.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    throw invalid("invalid percent-encoding in segment '" + segment + "'", url);
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String formDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("UTF-8 always supported", e);
        }
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    static Duration parseDuration(String value, String param, String url) throws ValetException {
        String s = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2).trim()));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException e) {
            throw invalid("invalid duration for " + param + ": '" + value + "' (use e.g. 30s, 500ms, 2m)", url);
        }
    }

    private static boolean parseBool(String value, String param, String url) throws ValetException {
        String s = value.trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) {
            return true;
        }
        if (s.equals("false")) {
            return false;
        }
        throw invalid("invalid boolean for " + param + ": '" + value + "' (use true or false)", url);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static ValetException invalid(String reason, String url) {
        return new ValetException("Invalid Boundary JDBC URL: " + reason + "."
                + "\n  URL:     " + url
                + "\n  Grammar: " + GRAMMAR, SqlStates.INVALID_URL);
    }

    /** Known boundary parameter names — exposed for {@code getPropertyInfo} and tests. */
    static List<String> knownParams() {
        return List.of(P_ADDR, P_CLI_PATH, P_CONNECT_TIMEOUT, P_IDLE_TIMEOUT, P_CREDENTIAL_NAME, P_BROKERED,
                P_TARGET_ID);
    }
}
