package io.github.zonarr.iou.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One immutable line of history: who did what to which entry, and how it looked before and after. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private AuditEntity entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "involved_user_ids", nullable = false, columnDefinition = "bigint[]")
    private Long[] involvedUserIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state")
    private String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state")
    private String afterState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AuditLog() {
    }

    public AuditLog(AuditEntity entityType, Long entityId, AuditAction action, Long groupId, Long actorId,
            Long[] involvedUserIds, String beforeState, String afterState) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.groupId = groupId;
        this.actorId = actorId;
        this.involvedUserIds = involvedUserIds;
        this.beforeState = beforeState;
        this.afterState = afterState;
    }

    public Long getId() {
        return id;
    }

    public AuditEntity getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public AuditAction getAction() {
        return action;
    }

    public Long getGroupId() {
        return groupId;
    }

    public Long getActorId() {
        return actorId;
    }

    public Long[] getInvolvedUserIds() {
        return involvedUserIds;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
