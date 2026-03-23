package com.creatorai.authservice.domain.model.role;

import com.creatorAI.common.AuditableModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Role extends AuditableModel {

    private Long id;
    private String name;
    private String description;
    private List<String> permissionNames = new ArrayList<>();

    private Role() {}

    // --- Factory Methods ---

    public static Role createNew(String name, String description, Long createdBy) {
        Role role = new Role();
        role.name = name.toUpperCase().trim();
        role.description = description;
        role.markCreated(createdBy);
        return role;
    }

    public static Role reconstitute(Long id, String name, String description,
                                    List<String> permissionNames,
                                    java.time.LocalDateTime createdAt,
                                    java.time.LocalDateTime updatedAt,
                                    Long createdBy, Long updatedBy,
                                    java.time.LocalDateTime deletedAt,
                                    Long deletedBy, boolean active) {
        Role role = new Role();
        role.id = id;
        role.name = name;
        role.description = description;
        role.permissionNames = new ArrayList<>(permissionNames);
        role.createdAt = createdAt;
        role.updatedAt = updatedAt;
        role.createdBy = createdBy;
        role.updatedBy = updatedBy;
        role.deletedAt = deletedAt;
        role.deletedBy = deletedBy;
        role.active = active;
        return role;
    }

    // --- Domain Behaviour ---

    public void grantPermission(String permissionName) {
        if (!this.permissionNames.contains(permissionName)) {
            this.permissionNames.add(permissionName);
        }
    }

    public void revokePermission(String permissionName) {
        this.permissionNames.remove(permissionName);
    }

    public boolean hasPermission(String permissionName) {
        return this.permissionNames.contains(permissionName);
    }

    public void updateDescription(String description, Long updatedBy) {
        this.description = description;
        this.markUpdated(updatedBy);
    }

    public void deactivate(Long deletedBy) {
        this.markDeleted(deletedBy);
    }

    // --- Getters ---

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<String> getPermissionNames() { return Collections.unmodifiableList(permissionNames); }
}
