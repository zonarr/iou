package io.github.zonarr.iou.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    @Query("SELECT e FROM Expense e WHERE e.groupId = :groupId AND e.deletedAt IS NULL ORDER BY e.spentOn DESC, e.id DESC")
    List<Expense> findActiveInGroup(long groupId);

    /** Expenses outside any group that the user paid for, shared in, or recorded. */
    @Query("""
            SELECT e FROM Expense e
            WHERE e.groupId IS NULL AND e.deletedAt IS NULL
              AND (e.createdBy = :userId
                   OR e.id IN (SELECT p.expenseId FROM ExpensePayer p WHERE p.userId = :userId)
                   OR e.id IN (SELECT s.expenseId FROM ExpenseShare s WHERE s.userId = :userId))
            ORDER BY e.spentOn DESC, e.id DESC
            """)
    List<Expense> findActiveDirectFor(long userId);
}
