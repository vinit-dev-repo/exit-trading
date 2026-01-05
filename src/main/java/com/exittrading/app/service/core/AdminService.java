package com.exittrading.app.service.core;

import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Service for administrative tasks, including user management and system defaults.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserAccountRepository userRepository;

    public AdminService(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void createDefaultUsers() {
        // No default users
    }

    public List<UserAccount> allUsers() {
        return userRepository.findAll();
    }

    public UserAccount findByUsername(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }

    public java.util.Optional<UserAccount> findOptionalByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Transactional
    public UserAccount ensureUserExists(String username, String displayName) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            UserAccount user = new UserAccount();
            user.setUsername(username);
            user.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : username);
            // holdings/loggingEnabled use defaults
            return userRepository.save(user);
        });
    }

    @Transactional
    public void toggleLogging(String username, boolean enabled) {
        userRepository.findByUsername(username).ifPresentOrElse(user -> {
            user.setLoggingEnabled(enabled);
            userRepository.save(user);
        }, () -> log.warn("toggleLogging called for unknown user '{}'", username));
    }

    @Transactional
    public void updateHoldings(String username, Set<String> holdings) {
        userRepository.findByUsername(username).ifPresentOrElse(user -> {
            user.setHoldings(holdings);
            userRepository.save(user);
        }, () -> log.warn("updateHoldings called for unknown user '{}'", username));
    }
}
