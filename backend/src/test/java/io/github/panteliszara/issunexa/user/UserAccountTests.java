package io.github.panteliszara.issunexa.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccountTests {

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void normalizesEmailAndTrimsDisplayNameWhilePreservingHashAndRole(UserRole role) {
        String passwordHash = "{bcrypt}encoded-test-value";

        UserAccount userAccount = new UserAccount(" \tAlice@Example.COM\n ", "  Alice  McKay\t", passwordHash, role);

        assertThat(userAccount.getEmail()).isEqualTo("alice@example.com");
        assertThat(userAccount.getDisplayName()).isEqualTo("Alice  McKay");
        assertThat(userAccount.getPasswordHash()).isEqualTo(passwordHash);
        assertThat(userAccount.getRole()).isEqualTo(role);
    }

    @Test
    void rejectsNullRole() {
        assertThatThrownBy(() -> new UserAccount("alice@example.com", "Alice", "{bcrypt}encoded-test-value", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("role must not be null");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t\n")
    void rejectsMissingOrBlankEmail(String email) {
        assertThatThrownBy(() -> new UserAccount(email, "Alice", "{bcrypt}encoded-test-value", UserRole.REQUESTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email must not be blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t\n")
    void rejectsMissingOrBlankDisplayName(String displayName) {
        assertThatThrownBy(() -> new UserAccount("alice@example.com", displayName,
                "{bcrypt}encoded-test-value", UserRole.REQUESTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("displayName must not be blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t\n")
    void rejectsMissingOrBlankPasswordHash(String passwordHash) {
        assertThatThrownBy(() -> new UserAccount("alice@example.com", "Alice", passwordHash, UserRole.REQUESTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("passwordHash must not be blank");
    }

}
