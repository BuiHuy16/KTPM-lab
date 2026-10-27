package com.example.userservice.controller;

import com.example.userservice.controller.UserDtos.*;
import com.example.userservice.service.*;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService users;
    private final AvatarService avatars;
    public UserController(UserService users, AvatarService avatars) { this.users = users; this.avatars = avatars; }
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) { return users.me(Long.parseLong(jwt.getSubject())); }
    @PostMapping("/avatar/upload-url")
    public UploadResponse uploadUrl(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AvatarRequest request) {
        return avatars.createUpload(Long.parseLong(jwt.getSubject()), request.contentType());
    }
}
