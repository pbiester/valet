package dev.isonet.valet.core;

/**
 * A username/password credential brokered by Boundary for a session.
 *
 * <p>{@code password} is secret material: {@link #toString()} redacts it so it cannot
 * leak into logs, IDE consoles or exception messages (§8.8).
 */
public record BrokeredCredential(
        String username, String password, String sourceName, String credentialType) {

    static final String USERNAME_PASSWORD = "username_password";

    public boolean isUsernamePassword() {
        return USERNAME_PASSWORD.equals(credentialType);
    }

    @Override
    public String toString() {
        return "BrokeredCredential[username=" + username
                + ", password=<redacted>"
                + ", sourceName=" + sourceName
                + ", credentialType=" + credentialType + "]";
    }
}
