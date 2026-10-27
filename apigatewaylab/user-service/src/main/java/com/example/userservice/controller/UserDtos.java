package com.example.userservice.controller;

import com.example.userservice.model.User;
import jakarta.validation.constraints.*;

public final class UserDtos {
    private UserDtos() {}
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,50}") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {}
    public record LoginRequest(@NotBlank @Size(max = 50) String username,
                               @NotBlank @Size(max = 72) String password) {}
    public record UserResponse(Long id, String username, String email, String avatarObjectKey) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getAvatarObjectKey());
        }
    }
    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
    public record AvatarRequest(@NotBlank @Pattern(regexp = "image/(png|jpeg|webp)") String contentType) {}
    public record UploadResponse(String uploadUrl, String objectKey, int expiresIn, String method) {}
}
