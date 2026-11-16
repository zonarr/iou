package io.github.zonarr.iou.ledger;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExpenseShareRepository extends JpaRepository<ExpenseShare, Long> {

    @Query("SELECT s FROM ExpenseShare s WHERE s.expenseId IN :expenseIds ORDER BY s.userId")
    List<ExpenseShare> findByExpenseIds(Collection<Long> expenseIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM ExpenseShare s WHERE s.expenseId = :expenseId")
    void deleteByExpenseId(long expenseId);
}
