package io.github.panteliszara.issunexa.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccountTests {

    @Test
    void normalizesEmailAndTrimsDisplayNameWhilePreservingHash() {
        String passwordHash = "{bcrypt}encoded-test-value";

        UserAccount userAccount = new UserAccount(" \tAlice@Example.COM\n ", "  Alice  McKay\t", passwordHash);

        assertThat(userAccount.getEmail()).isEqualTo("alice@example.com");
        assertThat(userAccount.getDisplayName()).isEqualTo("Alice  McKay");
        assertThat(userAccount.getPasswordHash()).isEqualTo(passwordHash);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t\n")
    void rejectsMissingOrBlankEmail(String email) {
        assertThatThrownBy(() -> new UserAccount(email, "Alice", "{bcrypt}encoded-test-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("email must not be blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t\n")
    void rejectsMissingOrBlankDisplayName(String displayName) {
        assertThatThrownBy(() -> new UserAccount("alice@example.com", displayName, "{bcrypt}encoded-test-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("displayName must not be blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t\n")
    void rejectsMissingOrBlankPasswordHash(String passwordHash) {
        assertThatThrownBy(() -> new UserAccount("alice@example.com", "Alice", passwordHash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("passwordHash must not be blank");
    }

}
