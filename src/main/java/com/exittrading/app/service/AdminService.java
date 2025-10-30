package com.exittrading.app.service;

import com.exittrading.app.domain.UserAccount;
import com.exittrading.app.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class AdminService {

    private final UserAccountRepository userRepository;

    public AdminService(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void createDefaultUsers() {
        if (userRepository.count() == 0) {
            UserAccount demo = new UserAccount();
            demo.setUsername("demo-user");
            demo.setDisplayName("Demo User");
            demo.setHoldings(Set.of("INFY", "TCS", "RELIANCE"));
            userRepository.save(demo);
        }
    }

    public List<UserAccount> allUsers() {
        return userRepository.findAll();
    }

    public UserAccount findByUsername(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }

    @Transactional
    public void toggleLogging(String username, boolean enabled) {
        UserAccount user = findByUsername(username);
        user.setLoggingEnabled(enabled);
        userRepository.save(user);
    }

    @Transactional
    public void updateHoldings(String username, Set<String> holdings) {
        UserAccount user = findByUsername(username);
        user.setHoldings(holdings);
        userRepository.save(user);
    }
}
