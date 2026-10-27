package com.example.userservice.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.example.userservice.controller.UserDtos.UploadResponse;
import io.minio.*;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AvatarService {
    public static final int EXPIRY_SECONDS = 300;
    private final MinioClient minio;
    private final String bucket;
    private final UserService users;

    public AvatarService(@Qualifier("publicMinio") MinioClient minio,
                         @Value("${lab.minio.bucket}") String bucket, UserService users) {
        this.minio = minio; this.bucket = bucket; this.users = users;
    }

    @Transactional
    public UploadResponse createUpload(long userId, String contentType) {
        var user = users.requireUser(userId);
        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported avatar type");
        };
        String key = "avatars/" + userId + "/" + UUID.randomUUID() + extension;
        try {
            String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT).bucket(bucket).object(key).expiry(EXPIRY_SECONDS, TimeUnit.SECONDS).build());
            user.setAvatarObjectKey(key);
            return new UploadResponse(url, key, EXPIRY_SECONDS, "PUT");
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to create avatar upload URL", error);
        }
    }
}
