package io.github.zonarr.iou.audit;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByIdAsc(AuditEntity entityType, long entityId);

    @Query("SELECT a FROM AuditLog a WHERE a.groupId = :groupId AND (:beforeId IS NULL OR a.id < :beforeId) ORDER BY a.id DESC")
    List<AuditLog> findForGroup(long groupId, Long beforeId, Pageable page);

    /** Everything a person may see: their groups' history plus entries that involve them directly. */
    @Query(value = """
            SELECT a.* FROM audit_log a
            WHERE (a.group_id IN (SELECT m.group_id FROM group_members m WHERE m.user_id = :userId)
                   OR (a.group_id IS NULL AND a.involved_user_ids @> ARRAY[:userId]::bigint[]))
              AND (CAST(:beforeId AS bigint) IS NULL OR a.id < CAST(:beforeId AS bigint))
            ORDER BY a.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<AuditLog> findForUser(long userId, Long beforeId, int limit);
}
