package io.github.panteliszara.issunexa.ticket;

public enum TicketSortField {
    CREATED_AT("createdAt"),
    UPDATED_AT("updatedAt"),
    TITLE("title");

    private final String propertyName;

    TicketSortField(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getPropertyName() {
        return propertyName;
    }
}
