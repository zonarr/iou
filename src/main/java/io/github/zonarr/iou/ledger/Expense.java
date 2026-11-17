package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.money.MoneyAmount;
import io.github.zonarr.iou.money.SplitType;
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
import java.time.LocalDate;

/** Something that was paid for and shared. Its payers, shares and debts live in their own tables. */
@Entity
@Table(name = "expenses")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id")
    private Long groupId;

    @Column(nullable = false)
    private String description;

    @Embedded
    private MoneyAmount money;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false)
    private SplitType splitType;

    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Expense() {
    }

    public Expense(Long groupId, String description, MoneyAmount money, SplitType splitType, LocalDate spentOn,
            Long createdBy) {
        this.groupId = groupId;
        this.description = description;
        this.money = money;
        this.splitType = splitType;
        this.spentOn = spentOn;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public String getDescription() {
        return description;
    }

    public MoneyAmount getMoney() {
        return money;
    }

    public SplitType getSplitType() {
        return splitType;
    }

    public LocalDate getSpentOn() {
        return spentOn;
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

    public void change(String description, MoneyAmount money, SplitType splitType, LocalDate spentOn) {
        this.description = description;
        this.money = money;
        this.splitType = splitType;
        this.spentOn = spentOn;
        this.updatedAt = Instant.now();
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
        this.updatedAt = this.deletedAt;
    }
}
