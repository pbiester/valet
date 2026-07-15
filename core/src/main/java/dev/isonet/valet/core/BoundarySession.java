package dev.isonet.valet.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The immutable data of one Boundary session, parsed from {@code boundary connect
 * -format=json} output (§5). Process control lives on {@link LiveSession}, not here.
 */
public record BoundarySession(
        String address, int port, String protocol,
        Instant expiration, int connectionLimit, String sessionId,
        List<BrokeredCredential> credentials) {

    public BoundarySession {
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
    }

    /**
     * True once the expiration is within {@code within} of {@code now} (or already past).
     * Used by the session cache to retire a session before the server tears it out from
     * under an in-flight connection.
     */
    public boolean isExpiredOrExpiringWithin(Duration within, Instant now) {
        // doomed  <=>  now + within >= expiration
        return expiration != null && !now.plus(within).isBefore(expiration);
    }

    /**
     * {@code connection_limit == 1} cannot support a connection pool (§5): the first
     * connection consumes the only slot and every subsequent one fails intermittently.
     * {@code -1} means unlimited; anything {@code > 1} is fine.
     */
    public boolean allowsPooling() {
        return connectionLimit == -1 || connectionLimit > 1;
    }

    /**
     * Select the single {@code username_password} credential to broker for a connection,
     * honouring the optional {@code boundary.credential-name} disambiguator (§5). Throws
     * rather than silently picking the first when the choice is ambiguous.
     */
    public BrokeredCredential selectUsernamePassword(Optional<String> credentialName, String targetLabel)
            throws ValetException {
        List<BrokeredCredential> matches = credentials.stream()
                .filter(BrokeredCredential::isUsernamePassword)
                .toList();

        if (credentialName.isPresent()) {
            String wanted = credentialName.get();
            List<BrokeredCredential> named = matches.stream()
                    .filter(c -> wanted.equals(c.sourceName()))
                    .toList();
            if (named.isEmpty()) {
                throw new ValetException(
                        "No brokered username/password credential named '" + wanted
                                + "' for " + targetLabel + ".",
                        SqlStates.UNABLE_TO_CONNECT);
            }
            if (named.size() > 1) {
                throw new ValetException(
                        "Multiple brokered credentials named '" + wanted + "' for " + targetLabel
                                + "; cannot disambiguate.",
                        SqlStates.UNABLE_TO_CONNECT);
            }
            return named.get(0);
        }

        if (matches.isEmpty()) {
            throw new ValetException(
                    "No brokered username/password credential for " + targetLabel
                            + ". Attach a username_password credential to the target as brokered,"
                            + " or set boundary.brokered-credentials=false to use caller credentials.",
                    SqlStates.UNABLE_TO_CONNECT);
        }
        if (matches.size() > 1) {
            List<String> names = matches.stream().map(BrokeredCredential::sourceName).toList();
            throw new ValetException(
                    "Multiple brokered username/password credentials for " + targetLabel
                            + "; set boundary.credential-name to one of: " + names,
                    SqlStates.UNABLE_TO_CONNECT);
        }
        return matches.get(0);
    }

    /** Redacted: delegates to {@link BrokeredCredential#toString()} for each credential. */
    @Override
    public String toString() {
        return "BoundarySession[address=" + address + ", port=" + port
                + ", protocol=" + protocol + ", expiration=" + expiration
                + ", connectionLimit=" + connectionLimit + ", sessionId=" + sessionId
                + ", credentials=" + credentials + "]";
    }
}
