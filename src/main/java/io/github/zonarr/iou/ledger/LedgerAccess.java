package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.group.GroupService;
import io.github.zonarr.iou.user.User;
import io.github.zonarr.iou.user.UserDirectory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Who may record what about whom. Shared by debts, expenses and payments. */
@Component
public class LedgerAccess {

    private final GroupService groups;
    private final UserDirectory directory;

    public LedgerAccess(GroupService groups, UserDirectory directory) {
        this.groups = groups;
        this.directory = directory;
    }

    /** The group if {@code groupId} is given (the actor must be a member), else null. */
    public Group groupFor(Long groupId, long actorId) {
        return groupId == null ? null : groups.requireMember(groupId, actorId);
    }

    /**
     * Loads the people an entry is about and checks they may be used.
     * In a group: all must be members. Outside a group: all must be registered accounts and
     * the person recording it must be one of them.
     */
    public Map<Long, User> requireParties(long actorId, Group group, Collection<Long> userIds) {
        Map<Long, User> people = new LinkedHashMap<>();
        for (Long id : new LinkedHashSet<>(userIds)) {
            if (id == null) {
                throw ApiException.badRequest("person_required", "A person is missing");
            }
            if (group != null) {
                if (!groups.isMember(group.getId(), id)) {
                    throw ApiException.badRequest("not_a_member", "Everyone involved must be a member of the group");
                }
                people.put(id, directory.require(id));
            } else {
                people.put(id, directory.requireAccount(id));
            }
        }
        if (group == null && !people.containsKey(actorId)) {
            throw ApiException.badRequest("must_involve_you",
                    "Outside a group you can only record things you are part of");
        }
        return people;
    }

    /** May the actor see this entry? Group entries: members. Others: the people involved. */
    public void requireCanSee(long actorId, Long groupId, Collection<Long> involvedUserIds) {
        boolean allowed = groupId != null
                ? groups.isMember(groupId, actorId)
                : involvedUserIds.contains(actorId);
        if (!allowed) {
            throw ApiException.notFound("Entry");
        }
    }
}
