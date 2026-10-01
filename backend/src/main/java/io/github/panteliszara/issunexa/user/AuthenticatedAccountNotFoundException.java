package io.github.panteliszara.issunexa.user;

public class AuthenticatedAccountNotFoundException extends IllegalStateException {

    public AuthenticatedAccountNotFoundException() {
        super("Authenticated user account could not be resolved.");
    }

}
