package io.github.panteliszara.issunexa.ticket;

import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

public final class TicketSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private TicketSpecifications() {
    }

    public static Specification<Ticket> hasStatus(TicketStatus status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    public static Specification<Ticket> hasPriority(TicketPriority priority) {
        return (root, query, builder) -> builder.equal(root.get("priority"), priority);
    }

    public static Specification<Ticket> containsText(String query) {
        String escaped = query.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        return (root, criteriaQuery, builder) -> builder.or(
                builder.like(builder.lower(root.get("title")), pattern, LIKE_ESCAPE),
                builder.like(builder.lower(root.get("description")), pattern, LIKE_ESCAPE));
    }

}
