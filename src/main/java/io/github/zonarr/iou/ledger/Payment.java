package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.money.MoneyAmount;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Money actually handed over: "from paid to". Counts once the receiver confirms it. */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "from_user_id", nullable = false)
    private Long fromUserId;

    @Column(name = "to_user_id", nullable = false)
    private Long toUserId;

    @Embedded
    private MoneyAmount money;

    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConfirmationStatus status;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Payment() {
    }

    public Payment(Long groupId, Long fromUserId, Long toUserId, MoneyAmount money, String note,
            ConfirmationStatus status, Long createdBy) {
        this.groupId = groupId;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.money = money;
        this.note = note;
        this.status = status;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public Long getFromUserId() {
        return fromUserId;
    }

    public Long getToUserId() {
        return toUserId;
    }

    public MoneyAmount getMoney() {
        return money;
    }

    public String getNote() {
        return note;
    }

    public ConfirmationStatus getStatus() {
        return status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void setStatus(ConfirmationStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void change(Long fromUserId, Long toUserId, MoneyAmount money, String note) {
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.money = money;
        this.note = note;
        this.updatedAt = Instant.now();
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
        this.updatedAt = this.deletedAt;
    }
}
