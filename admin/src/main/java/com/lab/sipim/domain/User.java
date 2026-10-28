package com.lab.sipim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "sip_id", nullable = false, unique = true)
    private String sipId;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "nickname")
    private String nickname;

    public String getNickname() {
        return nickname == null || nickname.isBlank() ? username : nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /** USER or ADMIN. Only 1001 is seeded as ADMIN for the chat audit page. */
    @Column(name = "role")
    private String role = "USER";

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role == null || role.isBlank() ? "USER" : role.trim().toUpperCase();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getSipId() {
        return sipId;
    }

    public void setSipId(String sipId) {
        this.sipId = sipId;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }
}
