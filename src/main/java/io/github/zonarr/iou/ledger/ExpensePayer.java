package io.github.zonarr.iou.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "expense_payers")
public class ExpensePayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expense_id", nullable = false)
    private Long expenseId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "original_amount", nullable = false)
    private BigDecimal originalAmount;

    @Column(name = "thb_amount", nullable = false)
    private BigDecimal thbAmount;

    protected ExpensePayer() {
    }

    public ExpensePayer(Long expenseId, Long userId, BigDecimal originalAmount, BigDecimal thbAmount) {
        this.expenseId = expenseId;
        this.userId = userId;
        this.originalAmount = originalAmount;
        this.thbAmount = thbAmount;
    }

    public Long getExpenseId() {
        return expenseId;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getThbAmount() {
        return thbAmount;
    }
}
