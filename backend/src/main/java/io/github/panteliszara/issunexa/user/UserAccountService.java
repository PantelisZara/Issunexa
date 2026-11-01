package io.github.panteliszara.issunexa.user;

import io.github.panteliszara.issunexa.demo.DemoWorkspaceAccess;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final DemoWorkspaceAccess demo;

    public UserAccountService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder, DemoWorkspaceAccess demo) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.demo = demo;
    }

    @Transactional(readOnly = true)
    public UserAccount getAuthenticatedAccount(String principalEmail) {
        UserAccount account = userAccountRepository.findByEmail(UserAccount.normalizeEmail(principalEmail))
                .orElseThrow(AuthenticatedAccountNotFoundException::new);
        demo.validate(account);
        return account;
    }

    @Transactional
    public UserAccount createUser(String email, String displayName, String rawPassword, UserRole role) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("rawPassword must not be blank");
        }
        Objects.requireNonNull(role, "role must not be null");
        String passwordHash = passwordEncoder.encode(rawPassword);
        UserAccount userAccount = new UserAccount(email, displayName, passwordHash, role);
        return userAccountRepository.save(userAccount);
    }

}
