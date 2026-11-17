package io.github.zonarr.iou.audit;

import io.github.zonarr.iou.audit.ActivityDtos.ActivityEntry;
import io.github.zonarr.iou.audit.ActivityDtos.ActivityPage;
import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.group.GroupRepository;
import io.github.zonarr.iou.group.GroupService;
import io.github.zonarr.iou.user.User;
import io.github.zonarr.iou.user.UserRepository;
import io.github.zonarr.iou.user.UserSummary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The user-facing timeline: the audit log, with ids turned into names. */
@Service
public class ActivityService {

    private static final int MAX_PAGE = 100;
    /** Fields of a snapshot that hold a user id. */
    private static final Set<String> USER_ID_FIELDS =
            Set.of("debtorId", "creditorId", "fromUserId", "toUserId", "userId");

    private final AuditLogRepository auditLog;
    private final GroupService groupService;
    private final GroupRepository groups;
    private final UserRepository users;
    private final JsonMapper json;

    public ActivityService(AuditLogRepository auditLog, GroupService groupService, GroupRepository groups,
            UserRepository users, JsonMapper json) {
        this.auditLog = auditLog;
        this.groupService = groupService;
        this.groups = groups;
        this.users = users;
        this.json = json;
    }

    /** History of one group; members only. */
    @Transactional(readOnly = true)
    public ActivityPage forGroup(long actorId, long groupId, Long before, Integer limit) {
        groupService.requireMember(groupId, actorId);
        int size = pageSize(limit);
        return page(auditLog.findForGroup(groupId, before, PageRequest.of(0, size + 1)), size);
    }

    /** Everything I am allowed to see: all my groups, plus one-on-one entries that involve me. */
    @Transactional(readOnly = true)
    public ActivityPage forUser(long actorId, Long before, Integer limit) {
        int size = pageSize(limit);
        return page(auditLog.findForUser(actorId, before, size + 1), size);
    }

    private ActivityPage page(List<AuditLog> rows, int size) {
        boolean more = rows.size() > size;
        List<AuditLog> shown = more ? rows.subList(0, size) : rows;

        Map<Long, JsonNode> beforeStates = new HashMap<>();
        Map<Long, JsonNode> afterStates = new HashMap<>();
        Set<Long> userIds = new HashSet<>();
        Set<Long> groupIds = new HashSet<>();
        for (AuditLog row : shown) {
            userIds.add(row.getActorId());
            if (row.getGroupId() != null) {
                groupIds.add(row.getGroupId());
            }
            if (row.getBeforeState() != null) {
                JsonNode node = json.readTree(row.getBeforeState());
                beforeStates.put(row.getId(), node);
                collectUserIds(node, userIds);
            }
            if (row.getAfterState() != null) {
                JsonNode node = json.readTree(row.getAfterState());
                afterStates.put(row.getId(), node);
                collectUserIds(node, userIds);
            }
        }

        Map<Long, User> people = loadPeople(userIds);
        Map<Long, String> groupNames = new HashMap<>();
        groups.findAllById(groupIds).forEach((Group g) -> groupNames.put(g.getId(), g.getName()));

        List<ActivityEntry> entries = new ArrayList<>();
        for (AuditLog row : shown) {
            User actor = people.get(row.getActorId());
            entries.add(new ActivityEntry(row.getId(), row.getCreatedAt(), row.getEntityType(), row.getEntityId(),
                    row.getAction(), row.getGroupId(), groupNames.get(row.getGroupId()),
                    actor == null ? null : UserSummary.of(actor),
                    beforeStates.get(row.getId()), afterStates.get(row.getId())));
        }
        Map<Long, String> names = new TreeMap<>();
        people.forEach((id, user) -> names.put(id, user.getDisplayName()));
        return new ActivityPage(entries, names, more ? shown.get(shown.size() - 1).getId() : null);
    }

    /**
     * Loads the people behind the ids. An old entry may still point at a placeholder that
     * has since been claimed; it is shown under the name of the account that claimed it.
     */
    private Map<Long, User> loadPeople(Set<Long> ids) {
        Map<Long, User> found = new HashMap<>();
        users.findAllById(ids).forEach(u -> found.put(u.getId(), u));
        Set<Long> claimedBy = new HashSet<>();
        found.values().forEach(u -> {
            if (u.getMergedInto() != null) {
                claimedBy.add(u.getMergedInto());
            }
        });
        Map<Long, User> owners = new HashMap<>();
        users.findAllById(claimedBy).forEach(u -> owners.put(u.getId(), u));
        Map<Long, User> result = new HashMap<>();
        found.forEach((id, user) -> result.put(id,
                user.getMergedInto() != null && owners.containsKey(user.getMergedInto())
                        ? owners.get(user.getMergedInto()) : user));
        return result;
    }

    private static void collectUserIds(JsonNode node, Set<Long> ids) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (USER_ID_FIELDS.contains(field.getKey()) && field.getValue().isIntegralNumber()) {
                    ids.add(field.getValue().asLong());
                } else {
                    collectUserIds(field.getValue(), ids);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectUserIds(child, ids));
        }
    }

    private static int pageSize(Integer limit) {
        return limit == null ? 30 : Math.max(1, Math.min(limit, MAX_PAGE));
    }
}
