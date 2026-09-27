package io.github.panteliszara.issunexa.shared.security;

import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTests {

    @Mock
    private UserAccountRepository userAccountRepository;

    private DatabaseUserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new DatabaseUserDetailsService(userAccountRepository);
    }

    @ParameterizedTest
    @CsvSource({"REQUESTER, ROLE_REQUESTER", "AGENT, ROLE_AGENT", "ADMIN, ROLE_ADMIN"})
    void looksUpCanonicalEmailAndReturnsStoredHashWithExactlyThePersistedRole(
            UserRole role, String expectedAuthority) {
        UserAccount userAccount = new UserAccount("alice@example.com", "Alice", "{bcrypt}encoded-test-value", role);
        when(userAccountRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(userAccount));

        UserDetails details = userDetailsService.loadUserByUsername(" \tAlice@Example.COM\n ");

        assertThat(details.getUsername()).isEqualTo("alice@example.com");
        assertThat(details.getPassword()).isEqualTo(userAccount.getPasswordHash());
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly(expectedAuthority);
        verify(userAccountRepository).findByEmail("alice@example.com");
        verifyNoMoreInteractions(userAccountRepository);
    }

    @Test
    void rejectsUnknownAccountWithoutIncludingLookupDetails() {
        when(userAccountRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("Missing@Example.COM"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("User account not found.");
        verify(userAccountRepository).findByEmail("missing@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " \t\n ")
    void rejectsMissingOrBlankUsernameWithoutQuerying(String username) {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(username))
                .isInstanceOf(UsernameNotFoundException.class);
        verifyNoInteractions(userAccountRepository);
    }

}
