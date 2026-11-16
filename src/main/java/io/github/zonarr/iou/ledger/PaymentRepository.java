package io.github.zonarr.iou.ledger;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("SELECT p FROM Payment p WHERE p.groupId = :groupId AND p.deletedAt IS NULL ORDER BY p.createdAt DESC, p.id DESC")
    List<Payment> findActiveInGroup(long groupId);

    @Query("""
            SELECT p FROM Payment p
            WHERE p.groupId IS NULL AND p.deletedAt IS NULL
              AND (p.fromUserId = :userId OR p.toUserId = :userId OR p.createdBy = :userId)
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Payment> findActiveDirectFor(long userId);

    /** Payments waiting for this person to confirm they received them. */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.toUserId = :userId AND p.status = io.github.zonarr.iou.ledger.ConfirmationStatus.PENDING
              AND p.deletedAt IS NULL
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Payment> findAwaiting(long userId);

    @Query("""
            SELECT p FROM Payment p
            WHERE p.status = io.github.zonarr.iou.ledger.ConfirmationStatus.CONFIRMED AND p.deletedAt IS NULL
              AND p.groupId = :groupId
            """)
    List<Payment> findConfirmedInGroup(long groupId);

    @Query("""
            SELECT p FROM Payment p
            WHERE p.status = io.github.zonarr.iou.ledger.ConfirmationStatus.CONFIRMED AND p.deletedAt IS NULL
              AND p.groupId IS NULL AND (p.fromUserId = :userId OR p.toUserId = :userId)
            """)
    List<Payment> findConfirmedDirectFor(long userId);
}
