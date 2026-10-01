package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Row of {@code diagrammer.user_account}.
 */
@Entity
@Table(name = "user_account", schema = "diagrammer")
public class UserAccount {

    private static final String EXTERNAL_ACCOUNT_PASSWORD_HASH = "!";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "external_subject", nullable = false, length = 512)
    private String externalSubject;

    @Column(name = "username", nullable = false, columnDefinition = "citext")
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "email", columnDefinition = "citext")
    private String email;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserAccount() {
    }

    public static UserAccount create(String externalSubject, String displayName, String email) {
        UserAccount user = new UserAccount();
        user.externalSubject = externalSubject;
        user.username = externalSubject;
        user.passwordHash = EXTERNAL_ACCOUNT_PASSWORD_HASH;
        user.displayName = displayName;
        user.email = email;
        user.status = "ACTIVE";
        OffsetDateTime now = OffsetDateTime.now();
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public static UserAccount register(String username, String passwordHash, String displayName, String email) {
        UserAccount user = create("local:" + UUID.randomUUID(), displayName, email);
        user.username = username;
        user.passwordHash = passwordHash;
        return user;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public void setExternalSubject(String externalSubject) {
        this.externalSubject = externalSubject;
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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
