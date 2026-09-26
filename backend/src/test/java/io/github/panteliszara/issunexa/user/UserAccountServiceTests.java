package io.github.panteliszara.issunexa.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTests {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserAccountService userAccountService;

    @BeforeEach
    void setUp() {
        userAccountService = new UserAccountService(userAccountRepository, passwordEncoder);
    }

    @Test
    void encodesUnmodifiedPasswordBeforeSavingAndReturnsRepositoryResult() {
        String rawPassword = "  test password with significant whitespace  ";
        String encodedPassword = "{bcrypt}encoded-test-value";
        UserAccount persistedUser = new UserAccount("alice@example.com", "Alice", encodedPassword);
        when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
        when(userAccountRepository.save(any(UserAccount.class))).thenReturn(persistedUser);

        UserAccount result = userAccountService.createUser("  Alice@Example.COM  ", " Alice ", rawPassword);

        verify(passwordEncoder).encode(rawPassword);
        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(userCaptor.capture());
        UserAccount submittedUser = userCaptor.getValue();
        assertThat(submittedUser.getEmail()).isEqualTo("alice@example.com");
        assertThat(submittedUser.getDisplayName()).isEqualTo("Alice");
        assertThat(submittedUser.getPasswordHash()).isEqualTo(encodedPassword).isNotEqualTo(rawPassword);
        assertThat(result).isSameAs(persistedUser);
        verifyNoMoreInteractions(passwordEncoder, userAccountRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t\n")
    void rejectsMissingOrBlankRawPasswordBeforeEncodingOrSaving(String rawPassword) {
        assertThatThrownBy(() -> userAccountService.createUser("alice@example.com", "Alice", rawPassword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rawPassword must not be blank");
        verifyNoInteractions(passwordEncoder, userAccountRepository);
    }

}
