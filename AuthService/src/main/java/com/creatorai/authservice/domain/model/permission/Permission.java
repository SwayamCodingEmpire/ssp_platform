package com.creatorai.authservice.domain.model.permission;

import com.creatorAI.common.AuditableModel;

public class Permission extends AuditableModel {

    private Long id;
    private String name;
    private String description;
    private String resource;
    private String action;

    private Permission() {}

    // --- Factory Methods ---

    public static Permission createNew(String resource, String action,
                                       String description, Long createdBy) {
        Permission permission = new Permission();
        permission.resource = resource.toUpperCase().trim();
        permission.action = action.toUpperCase().trim();
        permission.name = resource.toUpperCase().trim() + "_" + action.toUpperCase().trim();
        permission.description = description;
        permission.markCreated(createdBy);
        return permission;
    }

    public static Permission reconstitute(Long id, String name, String description,
                                          String resource, String action,
                                          java.time.LocalDateTime createdAt,
                                          java.time.LocalDateTime updatedAt,
                                          Long createdBy, Long updatedBy,
                                          java.time.LocalDateTime deletedAt,
                                          Long deletedBy, boolean active) {
        Permission permission = new Permission();
        permission.id = id;
        permission.name = name;
        permission.description = description;
        permission.resource = resource;
        permission.action = action;
        permission.createdAt = createdAt;
        permission.updatedAt = updatedAt;
        permission.createdBy = createdBy;
        permission.updatedBy = updatedBy;
        permission.deletedAt = deletedAt;
        permission.deletedBy = deletedBy;
        permission.active = active;
        return permission;
    }

    // --- Domain Behaviour ---

    public void deactivate(Long deletedBy) {
        this.markDeleted(deletedBy);
    }

    // --- Getters ---

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getResource() { return resource; }
    public String getAction() { return action; }
}
