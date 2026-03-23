package com.creatorai.authservice.domain.model.user;

import com.creatorAI.common.AuditableModel;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class User extends AuditableModel {

    private Long id;
    private String email;
    private String passwordHash;
    private String firstName;
    private String lastName;
    private String preferredLanguage;
    private boolean emailVerified;
    private LocalDateTime emailVerifiedAt;
    private List<String> roleNames = new ArrayList<>();

    private User() {}

    // --- Factory Methods ---

    public static User createNew(String email, String passwordHash,
                                 String firstName, String lastName,
                                 String preferredLanguage) {
        User user = new User();
        user.email = email.toLowerCase().trim();
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.preferredLanguage = preferredLanguage;
        user.emailVerified = false;
        user.markCreated(null);
        return user;
    }

    public static User createNewByAdmin(String email, String passwordHash,
                                        String firstName, String lastName,
                                        String preferredLanguage, Long adminId) {
        User user = new User();
        user.email = email.toLowerCase().trim();
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.preferredLanguage = preferredLanguage;
        user.emailVerified = false;
        user.markCreated(adminId);
        return user;
    }

    public static User reconstitute(Long id, String email, String passwordHash,
                                    String firstName, String lastName,
                                    String preferredLanguage, boolean emailVerified,
                                    LocalDateTime emailVerifiedAt, List<String> roleNames,
                                    LocalDateTime createdAt, LocalDateTime updatedAt,
                                    Long createdBy, Long updatedBy,
                                    LocalDateTime deletedAt, Long deletedBy,
                                    boolean active) {
        User user = new User();
        user.id = id;
        user.email = email;
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.preferredLanguage = preferredLanguage;
        user.emailVerified = emailVerified;
        user.emailVerifiedAt = emailVerifiedAt;
        user.roleNames = new ArrayList<>(roleNames);
        user.createdAt = createdAt;
        user.updatedAt = updatedAt;
        user.createdBy = createdBy;
        user.updatedBy = updatedBy;
        user.deletedAt = deletedAt;
        user.deletedBy = deletedBy;
        user.active = active;
        return user;
    }

    // --- Domain Behaviour ---

    public void verifyEmail() {
        this.emailVerified = true;
        this.emailVerifiedAt = LocalDateTime.now();
        this.markUpdated(this.id);
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.markUpdated(this.id);
    }

    public void updateProfile(String firstName, String lastName, String preferredLanguage) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.preferredLanguage = preferredLanguage;
        this.markUpdated(this.id);
    }

    public void assignRole(String roleName) {
        if (!this.roleNames.contains(roleName)) {
            this.roleNames.add(roleName);
        }
    }

    public void revokeRole(String roleName) {
        this.roleNames.remove(roleName);
    }

    public void deactivate(Long deletedBy) {
        this.markDeleted(deletedBy);
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    // --- Getters ---

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public boolean isEmailVerified() { return emailVerified; }
    public LocalDateTime getEmailVerifiedAt() { return emailVerifiedAt; }
    public List<String> getRoleNames() { return Collections.unmodifiableList(roleNames); }
}
