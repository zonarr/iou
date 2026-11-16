package io.github.zonarr.iou.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A person who can owe or be owed money. Either a real account (email + password) or a
 * placeholder: a name added to a group for someone who has not signed up yet.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean placeholder;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "merged_into")
    private Long mergedInto;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected User() {
    }

    public static User account(String email, String passwordHash, String displayName) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.placeholder = false;
        return user;
    }

    public static User placeholder(String displayName, Long createdBy) {
        User user = new User();
        user.displayName = displayName;
        user.placeholder = true;
        user.createdBy = createdBy;
        return user;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
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

    public boolean isPlaceholder() {
        return placeholder;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getMergedInto() {
        return mergedInto;
    }

    /** Marks a claimed placeholder: it stays as a tombstone pointing at the real account. */
    public void markMergedInto(Long userId) {
        this.mergedInto = userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
