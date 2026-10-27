package com.example.userservice.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.example.userservice.controller.UserDtos.*;
import com.example.userservice.model.User;
import com.example.userservice.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final String dummyHash;

    public UserService(UserRepository users, PasswordEncoder passwords, TokenService tokens) {
        this.users = users; this.passwords = passwords; this.tokens = tokens;
        dummyHash = passwords.encode("invalid-account-timing-padding");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        validatePasswordLength(request.password());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByUsernameOrEmail(request.username(), email))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or email already registered");
        return UserResponse.from(users.saveAndFlush(new User(request.username(), email, passwords.encode(request.password()))));
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        validatePasswordLength(request.password());
        var user = users.findByUsername(request.username());
        boolean matches = passwords.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        return tokens.issue(user.orElseThrow().getId());
    }

    @Transactional(readOnly = true)
    public UserResponse me(long userId) { return UserResponse.from(requireUser(userId)); }

    public User requireUser(long userId) {
        return users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BCrypt password must be at most 72 UTF-8 bytes");
    }
}
