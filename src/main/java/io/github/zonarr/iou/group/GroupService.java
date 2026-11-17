package io.github.zonarr.iou.group;

import io.github.zonarr.iou.audit.AuditAction;
import io.github.zonarr.iou.audit.AuditEntity;
import io.github.zonarr.iou.audit.AuditService;
import io.github.zonarr.iou.auth.AuthService;
import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.GroupDtos.GroupDetail;
import io.github.zonarr.iou.group.GroupDtos.GroupSummary;
import io.github.zonarr.iou.group.GroupDtos.InvitePreview;
import io.github.zonarr.iou.group.GroupDtos.MemberView;
import io.github.zonarr.iou.user.User;
import io.github.zonarr.iou.user.UserRepository;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {

    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 22;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final UserRepository users;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public GroupService(GroupRepository groups, GroupMemberRepository members, UserRepository users,
            JdbcClient jdbc, AuditService audit) {
        this.audit = audit;
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.jdbc = jdbc;
    }

    // ---- access checks used across the application ----

    /** Returns the group, or 404 if it does not exist or the user is not in it (no leaking which). */
    @Transactional(readOnly = true)
    public Group requireMember(long groupId, long userId) {
        Group group = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Group"));
        if (!members.existsByGroupIdAndUserId(groupId, userId)) {
            throw ApiException.notFound("Group");
        }
        return group;
    }

    public Group requireOwner(long groupId, long userId) {
        Group group = requireMember(groupId, userId);
        if (!group.getOwnerId().equals(userId)) {
            throw ApiException.forbidden("owner_only", "Only the group owner can do this");
        }
        return group;
    }

    @Transactional(readOnly = true)
    public boolean isMember(long groupId, long userId) {
        return members.existsByGroupIdAndUserId(groupId, userId);
    }

    // ---- groups ----

    @Transactional
    public GroupDetail create(long actorId, String name) {
        Group group = groups.save(new Group(name.trim(), actorId, newInviteCode()));
        members.save(new GroupMember(group.getId(), actorId));
        audit.record(AuditEntity.GROUP, group.getId(), AuditAction.CREATED, group.getId(), actorId, List.of(),
                null, settings(group));
        return detail(group);
    }

    @Transactional(readOnly = true)
    public List<GroupSummary> listFor(long actorId) {
        List<Group> mine = groups.findAllForUser(actorId);
        Map<Long, Long> counts = members.findByGroupIdIn(mine.stream().map(Group::getId).toList()).stream()
                .collect(Collectors.groupingBy(GroupMember::getGroupId, Collectors.counting()));
        return mine.stream()
                .map(g -> new GroupSummary(g.getId(), g.getName(), g.getOwnerId(), g.isAutoConfirm(),
                        counts.getOrDefault(g.getId(), 0L).intValue(), g.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupDetail get(long actorId, long groupId) {
        return detail(requireMember(groupId, actorId));
    }

    @Transactional
    public GroupDetail update(long actorId, long groupId, String name, Boolean autoConfirm) {
        Group group = requireOwner(groupId, actorId);
        Map<String, Object> before = settings(group);
        if (name != null && !name.isBlank()) {
            group.setName(name.trim());
        }
        if (autoConfirm != null) {
            group.setAutoConfirm(autoConfirm);
        }
        Map<String, Object> after = settings(group);
        if (!before.equals(after)) {
            audit.record(AuditEntity.GROUP, groupId, AuditAction.UPDATED, groupId, actorId, List.of(), before, after);
        }
        return detail(group);
    }

    @Transactional
    public GroupDetail resetInviteCode(long actorId, long groupId) {
        Group group = requireOwner(groupId, actorId);
        group.setInviteCode(newInviteCode());
        audit.record(AuditEntity.GROUP, groupId, AuditAction.INVITE_RESET, groupId, actorId, List.of(), null, null);
        return detail(group);
    }

    // ---- members ----

    @Transactional
    public GroupDetail addPlaceholder(long actorId, long groupId, String name) {
        Group group = requireMember(groupId, actorId);
        String trimmed = name.trim();
        requireNameFree(groupId, trimmed, null);
        User placeholder = users.save(User.placeholder(trimmed, actorId));
        members.save(new GroupMember(groupId, placeholder.getId()));
        audit.record(AuditEntity.MEMBER, placeholder.getId(), AuditAction.ADDED, groupId, actorId,
                List.of(placeholder.getId()), null, memberState(placeholder));
        return detail(group);
    }

    @Transactional
    public GroupDetail addByEmail(long actorId, long groupId, String email) {
        Group group = requireMember(groupId, actorId);
        User user = users.findByEmail(AuthService.normalizeEmail(email))
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "user_not_found", "Nobody has registered with that email yet"));
        if (members.existsByGroupIdAndUserId(groupId, user.getId())) {
            throw ApiException.conflict("already_member", "That person is already in the group");
        }
        members.save(new GroupMember(groupId, user.getId()));
        audit.record(AuditEntity.MEMBER, user.getId(), AuditAction.ADDED, groupId, actorId,
                List.of(user.getId()), null, memberState(user));
        return detail(group);
    }

    @Transactional
    public GroupDetail renamePlaceholder(long actorId, long groupId, long memberUserId, String name) {
        Group group = requireMember(groupId, actorId);
        User user = requirePlaceholderIn(groupId, memberUserId);
        String trimmed = name.trim();
        requireNameFree(groupId, trimmed, memberUserId);
        Map<String, Object> before = memberState(user);
        user.setDisplayName(trimmed);
        audit.record(AuditEntity.MEMBER, memberUserId, AuditAction.RENAMED, groupId, actorId,
                List.of(memberUserId), before, memberState(user));
        return detail(group);
    }

    /**
     * Removes a member who has no history in the group. The owner can remove anybody but
     * themselves; anybody else can only remove themselves (leave) or a placeholder.
     */
    @Transactional
    public GroupDetail removeMember(long actorId, long groupId, long memberUserId) {
        Group group = requireMember(groupId, actorId);
        GroupMember membership = members.findByGroupIdAndUserId(groupId, memberUserId)
                .orElseThrow(() -> ApiException.notFound("Member"));
        User user = users.findById(memberUserId).orElseThrow(() -> ApiException.notFound("Member"));
        if (group.getOwnerId().equals(memberUserId)) {
            throw ApiException.badRequest("owner_cannot_leave", "The group owner cannot be removed");
        }
        boolean allowed = group.getOwnerId().equals(actorId) || actorId == memberUserId || user.isPlaceholder();
        if (!allowed) {
            throw ApiException.forbidden("owner_only", "Only the group owner can remove another member");
        }
        if (hasHistory(groupId, memberUserId)) {
            throw ApiException.conflict("member_has_history",
                    "This person has expenses, debts or payments in the group and cannot be removed");
        }
        members.delete(membership);
        // The placeholder row itself may be deleted below, so the history line names nobody by id.
        audit.record(AuditEntity.MEMBER, memberUserId, AuditAction.REMOVED, groupId, actorId,
                user.isPlaceholder() ? List.of() : List.of(memberUserId), memberState(user), null);
        if (user.isPlaceholder()) {
            users.delete(user);
        }
        members.flush();
        return detail(group);
    }

    private boolean hasHistory(long groupId, long userId) {
        Integer count = jdbc.sql("""
                SELECT (SELECT count(*) FROM debts WHERE group_id = :g AND (debtor_id = :u OR creditor_id = :u))
                     + (SELECT count(*) FROM payments WHERE group_id = :g AND (from_user_id = :u OR to_user_id = :u))
                     + (SELECT count(*) FROM expense_payers p JOIN expenses e ON e.id = p.expense_id
                         WHERE e.group_id = :g AND p.user_id = :u)
                     + (SELECT count(*) FROM expense_shares s JOIN expenses e ON e.id = s.expense_id
                         WHERE e.group_id = :g AND s.user_id = :u)
                """).param("g", groupId).param("u", userId).query(Integer.class).single();
        return count > 0;
    }

    // ---- invites ----

    @Transactional(readOnly = true)
    public InvitePreview previewInvite(long actorId, String code) {
        Group group = requireInvite(code);
        List<MemberView> all = memberViews(group.getId());
        return new InvitePreview(group.getId(), group.getName(),
                members.existsByGroupIdAndUserId(group.getId(), actorId), all.size(),
                all.stream().filter(MemberView::placeholder).toList());
    }

    /** Joins as a new member. Joining twice is harmless. */
    @Transactional
    public GroupDetail join(long actorId, String code) {
        Group group = requireInvite(code);
        if (!members.existsByGroupIdAndUserId(group.getId(), actorId)) {
            members.save(new GroupMember(group.getId(), actorId));
            audit.record(AuditEntity.MEMBER, actorId, AuditAction.JOINED, group.getId(), actorId, List.of(),
                    null, memberState(users.findById(actorId).orElseThrow()));
        }
        return detail(group);
    }

    public Group requireInvite(String code) {
        return groups.findByInviteCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "invite_not_found", "This invite link is not valid any more"));
    }

    // ---- helpers ----

    public User requirePlaceholderIn(long groupId, long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Member"));
        if (!user.isPlaceholder() || user.getMergedInto() != null
                || !members.existsByGroupIdAndUserId(groupId, userId)) {
            throw ApiException.notFound("Placeholder");
        }
        return user;
    }

    private static Map<String, Object> settings(Group group) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", group.getName());
        map.put("autoConfirm", group.isAutoConfirm());
        return map;
    }

    static Map<String, Object> memberState(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", user.getId());
        map.put("name", user.getDisplayName());
        map.put("placeholder", user.isPlaceholder());
        return map;
    }

    private void requireNameFree(long groupId, String name, Long exceptUserId) {
        boolean taken = memberViews(groupId).stream()
                .anyMatch(m -> !m.id().equals(exceptUserId) && m.displayName().equalsIgnoreCase(name));
        if (taken) {
            throw ApiException.conflict("name_taken", "Somebody in the group already has that name");
        }
    }

    public GroupDetail detail(Group group) {
        members.flush();
        return new GroupDetail(group.getId(), group.getName(), group.getOwnerId(), group.isAutoConfirm(),
                group.getInviteCode(), memberViews(group.getId()), group.getCreatedAt());
    }

    private List<MemberView> memberViews(long groupId) {
        List<GroupMember> rows = members.findByGroupIdOrderByJoinedAtAscIdAsc(groupId);
        Map<Long, User> byId = users.findAllById(rows.stream().map(GroupMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream()
                .map(m -> {
                    User u = byId.get(m.getUserId());
                    return new MemberView(u.getId(), u.getDisplayName(), u.isPlaceholder(), m.getJoinedAt());
                })
                .toList();
    }

    private static String newInviteCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }
}
