package io.github.panteliszara.issunexa.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
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
