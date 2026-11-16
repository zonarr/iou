package io.github.zonarr.iou.ledger;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExpensePayerRepository extends JpaRepository<ExpensePayer, Long> {

    @Query("SELECT p FROM ExpensePayer p WHERE p.expenseId IN :expenseIds ORDER BY p.userId")
    List<ExpensePayer> findByExpenseIds(Collection<Long> expenseIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM ExpensePayer p WHERE p.expenseId = :expenseId")
    void deleteByExpenseId(long expenseId);
}
