package io.github.zonarr.iou.group;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GroupRepository extends JpaRepository<Group, Long> {

    Optional<Group> findByInviteCode(String inviteCode);

    @Query("""
            SELECT g FROM Group g
            WHERE g.id IN (SELECT m.groupId FROM GroupMember m WHERE m.userId = :userId)
            ORDER BY g.createdAt DESC, g.id DESC
            """)
    List<Group> findAllForUser(long userId);
}
