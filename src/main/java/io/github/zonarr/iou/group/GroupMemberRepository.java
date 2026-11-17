package io.github.zonarr.iou.group;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    boolean existsByGroupIdAndUserId(long groupId, long userId);

    Optional<GroupMember> findByGroupIdAndUserId(long groupId, long userId);

    List<GroupMember> findByGroupIdOrderByJoinedAtAscIdAsc(long groupId);

    List<GroupMember> findByGroupIdIn(Collection<Long> groupIds);

    List<GroupMember> findByUserId(long userId);
}
