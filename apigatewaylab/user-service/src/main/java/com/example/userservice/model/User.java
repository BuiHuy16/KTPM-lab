package com.example.userservice.model;

import jakarta.persistence.*;

@Entity
@Table(name = "app_users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 50)
    private String username;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(length = 512)
    private String avatarObjectKey;

    protected User() {}
    public User(String username, String email, String passwordHash) {
        this.username = username; this.email = email; this.passwordHash = passwordHash;
    }
    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getAvatarObjectKey() { return avatarObjectKey; }
    public void setAvatarObjectKey(String key) { avatarObjectKey = key; }
}
