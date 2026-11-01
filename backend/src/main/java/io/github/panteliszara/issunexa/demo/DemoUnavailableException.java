package io.github.panteliszara.issunexa.demo;

public class DemoUnavailableException extends RuntimeException {
    public DemoUnavailableException() {
        super("The demo is temporarily unavailable. Try again later.");
    }
}
