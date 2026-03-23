package com.creatorAI.common;

import java.time.LocalDateTime;

public abstract class AuditableModel {

    protected LocalDateTime createdAt;
    protected LocalDateTime updatedAt;
    protected LocalDateTime deletedAt;
    protected Long createdBy;
    protected Long updatedBy;
    protected Long deletedBy;
    protected boolean active = true;

    protected void markCreated(Long createdBy) {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
        this.active = true;
    }

    protected void markUpdated(Long updatedBy) {
        this.updatedAt = LocalDateTime.now();
        this.updatedBy = updatedBy;
    }

    protected void markDeleted(Long deletedBy) {
        this.deletedAt = LocalDateTime.now();
        this.deletedBy = deletedBy;
        this.active = false;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public Long getDeletedBy() { return deletedBy; }
    public boolean isActive() { return active; }
}
