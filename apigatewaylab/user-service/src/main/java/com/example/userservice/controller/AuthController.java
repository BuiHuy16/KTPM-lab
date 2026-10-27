package com.example.userservice.controller;

import com.example.userservice.controller.UserDtos.*;
import com.example.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserService users;
    public AuthController(UserService users) { this.users = users; }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) { return users.register(request); }
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) { return users.login(request); }
}
