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

/** "Debtor owes creditor". Either a standalone IOU or one line generated from an expense. */
@Entity
@Table(name = "debts")
public class Debt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "expense_id")
    private Long expenseId;

    @Column(name = "debtor_id", nullable = false)
    private Long debtorId;

    @Column(name = "creditor_id", nullable = false)
    private Long creditorId;

    @Embedded
    private MoneyAmount money;

    @Column(nullable = false)
    private String description;

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

    protected Debt() {
    }

    public Debt(Long groupId, Long expenseId, Long debtorId, Long creditorId, MoneyAmount money,
            String description, ConfirmationStatus status, Long createdBy) {
        this.groupId = groupId;
        this.expenseId = expenseId;
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.money = money;
        this.description = description;
        this.status = status;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public Long getExpenseId() {
        return expenseId;
    }

    public Long getDebtorId() {
        return debtorId;
    }

    public Long getCreditorId() {
        return creditorId;
    }

    public MoneyAmount getMoney() {
        return money;
    }

    public String getDescription() {
        return description;
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void setStatus(ConfirmationStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void change(Long debtorId, Long creditorId, MoneyAmount money, String description) {
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.money = money;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void setDescription(String description) {
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
        this.updatedAt = this.deletedAt;
    }
}
