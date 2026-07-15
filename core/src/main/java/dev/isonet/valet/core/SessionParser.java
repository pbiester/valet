package dev.isonet.valet.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code boundary connect -format=json} output into a {@link BoundarySession} (§5).
 *
 * <p>Design points that are each a real trap:
 * <ul>
 *   <li>Unknown properties are ignored, so a Boundary upgrade adding a field cannot break
 *       the driver.</li>
 *   <li>{@code expiration} is bound as a {@code String} and converted with
 *       {@link Instant#parse} — it is RFC-3339 with microsecond precision and a {@code Z},
 *       which {@code Instant.parse} handles directly, so no jackson-jsr310 module is needed.</li>
 *   <li>{@link #readOne(InputStream)} reads exactly one JSON value and does <em>not</em>
 *       close the stream — the caller must keep draining it (§8.3). The factory disables
 *       {@code AUTO_CLOSE_SOURCE} for that reason.</li>
 *   <li>The credential is taken from {@code credential}, falling back to
 *       {@code secret.decoded}. {@code secret.raw} is deliberately not retained: it is
 *       secret material and keeping it only invites a redaction leak.</li>
 * </ul>
 */
public final class SessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static {
        // The proxy keeps writing after the JSON; do not let Jackson close the stream on us.
        MAPPER.getFactory().disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
    }

    private SessionParser() {}

    /**
     * Read exactly one session object from {@code in}, stopping at the end of the first
     * complete JSON value (works for both compact and pretty-printed output, §8.2). The
     * stream is left open for draining.
     */
    public static BoundarySession readOne(InputStream in) throws IOException {
        JsonParser parser = MAPPER.getFactory().createParser(in);
        SessionDto dto = MAPPER.readValue(parser, SessionDto.class);
        return fromDto(dto);
    }

    static BoundarySession fromDto(SessionDto dto) {
        Instant expiration = (dto.expiration == null || dto.expiration.isBlank())
                ? null : Instant.parse(dto.expiration);

        List<BrokeredCredential> creds = new ArrayList<>();
        if (dto.credentials != null) {
            for (CredentialEntryDto entry : dto.credentials) {
                if (entry != null) {
                    creds.add(toCredential(entry));
                }
            }
        }
        return new BoundarySession(dto.address, dto.port, dto.protocol, expiration,
                dto.connectionLimit, dto.sessionId, creds);
    }

    private static BrokeredCredential toCredential(CredentialEntryDto entry) {
        Map<String, String> primary = entry.credential;                       // preferred (§5)
        Map<String, String> fallback = entry.secret == null ? null : entry.secret.decoded;
        String username = pick(primary, fallback, "username");
        String password = pick(primary, fallback, "password");
        String sourceName = entry.credentialSource == null ? null : entry.credentialSource.name;
        String credType = entry.credentialSource == null ? null : entry.credentialSource.credentialType;
        return new BrokeredCredential(username, password, sourceName, credType);
    }

    private static String pick(Map<String, String> primary, Map<String, String> fallback, String key) {
        if (primary != null && primary.get(key) != null) {
            return primary.get(key);
        }
        return fallback == null ? null : fallback.get(key);
    }

    // ---- Jackson DTOs (mirror the JSON exactly; converted to the domain model above). ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class SessionDto {
        public String address;
        public int port;
        public String protocol;
        public String expiration;
        @JsonProperty("connection_limit")
        public int connectionLimit = -1;
        @JsonProperty("session_id")
        public String sessionId;
        public List<CredentialEntryDto> credentials;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class CredentialEntryDto {
        @JsonProperty("credential_source")
        public CredentialSourceDto credentialSource;
        public SecretDto secret;
        public Map<String, String> credential;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class CredentialSourceDto {
        public String id;
        public String name;
        @JsonProperty("credential_store_id")
        public String credentialStoreId;
        public String type;
        @JsonProperty("credential_type")
        public String credentialType;
    }

    /** {@code raw} (base64 secret material) is intentionally absent — see class notes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class SecretDto {
        public Map<String, String> decoded;
    }
}
