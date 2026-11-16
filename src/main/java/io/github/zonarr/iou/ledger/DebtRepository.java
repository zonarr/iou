package io.github.zonarr.iou.ledger;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DebtRepository extends JpaRepository<Debt, Long> {

    @Query("SELECT d FROM Debt d WHERE d.groupId = :groupId AND d.deletedAt IS NULL ORDER BY d.createdAt DESC, d.id DESC")
    List<Debt> findActiveInGroup(long groupId);

    /** One-on-one debts (no group) that the user is part of or recorded. */
    @Query("""
            SELECT d FROM Debt d
            WHERE d.groupId IS NULL AND d.deletedAt IS NULL
              AND (d.debtorId = :userId OR d.creditorId = :userId OR d.createdBy = :userId)
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    List<Debt> findActiveDirectFor(long userId);

    @Query("SELECT d FROM Debt d WHERE d.expenseId IN :expenseIds AND d.deletedAt IS NULL ORDER BY d.debtorId, d.creditorId")
    List<Debt> findActiveByExpenseIds(Collection<Long> expenseIds);

    /** Debts waiting for this person to accept or reject. */
    @Query("""
            SELECT d FROM Debt d
            WHERE d.debtorId = :userId AND d.status = io.github.zonarr.iou.ledger.ConfirmationStatus.PENDING
              AND d.deletedAt IS NULL
            ORDER BY d.createdAt DESC, d.id DESC
            """)
    List<Debt> findAwaiting(long userId);

    @Query("""
            SELECT d FROM Debt d
            WHERE d.status = io.github.zonarr.iou.ledger.ConfirmationStatus.CONFIRMED AND d.deletedAt IS NULL
              AND d.groupId = :groupId
            """)
    List<Debt> findConfirmedInGroup(long groupId);

    @Query("""
            SELECT d FROM Debt d
            WHERE d.status = io.github.zonarr.iou.ledger.ConfirmationStatus.CONFIRMED AND d.deletedAt IS NULL
              AND d.groupId IS NULL AND (d.debtorId = :userId OR d.creditorId = :userId)
            """)
    List<Debt> findConfirmedDirectFor(long userId);
}
