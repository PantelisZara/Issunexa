package io.github.panteliszara.issunexa.shared.web;

import org.springframework.web.bind.ServletRequestBindingException;

public final class PaginationValidation {

    private PaginationValidation() {
    }

    public static void validateOffset(int page, int size) throws ServletRequestBindingException {
        // JPA accepts an int offset even though PageRequest computes it as a long.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ServletRequestBindingException("Page offset exceeds the supported range.");
        }
    }

}
