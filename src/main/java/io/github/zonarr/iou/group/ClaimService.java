package io.github.zonarr.iou.group;

import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.GroupDtos.GroupDetail;
import io.github.zonarr.iou.user.User;
import io.github.zonarr.iou.user.UserRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claiming a placeholder: a registered user says "that name is me". Every debt, payment and
 * expense share that pointed at the placeholder is re-pointed at the real account, so the
 * whole history moves with it. From then on the normal confirmation flow applies.
 */
@Service
public class ClaimService {

    private final GroupService groupService;
    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final UserRepository users;
    private final JdbcClient jdbc;

    public ClaimService(GroupService groupService, GroupRepository groups, GroupMemberRepository members,
            UserRepository users, JdbcClient jdbc) {
        this.groupService = groupService;
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.jdbc = jdbc;
    }

    /**
     * @param inviteCode proves the caller was invited; not needed if they are already a member
     */
    @Transactional
    public GroupDetail claim(long actorId, long placeholderId, String inviteCode) {
        User placeholder = users.findById(placeholderId)
                .filter(u -> u.isPlaceholder() && u.getMergedInto() == null)
                .orElseThrow(() -> ApiException.notFound("Placeholder"));
        List<GroupMember> memberships = members.findByUserId(placeholderId);
        if (memberships.isEmpty()) {
            throw ApiException.notFound("Placeholder");
        }
        long groupId = memberships.get(0).getGroupId();
        Group group = groups.findById(groupId).orElseThrow(() -> ApiException.notFound("Placeholder"));

        boolean alreadyMember = members.existsByGroupIdAndUserId(groupId, actorId);
        boolean invited = inviteCode != null && group.getInviteCode().equals(inviteCode.trim());
        if (!alreadyMember && !invited) {
            // Same answer as "does not exist", so placeholder ids cannot be probed.
            throw ApiException.notFound("Placeholder");
        }
        if (alreadyMember) {
            requireNoSharedHistory(actorId, placeholderId);
        }

        repoint("debts", "debtor_id", placeholderId, actorId);
        repoint("debts", "creditor_id", placeholderId, actorId);
        repoint("payments", "from_user_id", placeholderId, actorId);
        repoint("payments", "to_user_id", placeholderId, actorId);
        repoint("expense_payers", "user_id", placeholderId, actorId);
        repoint("expense_shares", "user_id", placeholderId, actorId);
        jdbc.sql("""
                UPDATE audit_log SET involved_user_ids = array_replace(involved_user_ids, :from, :to)
                WHERE involved_user_ids @> ARRAY[:from]::bigint[]
                """).param("from", placeholderId).param("to", actorId).update();

        if (alreadyMember) {
            members.delete(memberships.get(0));
        } else {
            memberships.get(0).reassignTo(actorId);
        }
        placeholder.markMergedInto(actorId);
        users.flush();
        members.flush();
        return groupService.detail(group);
    }

    /**
     * If the claimer is already in the group and already has dealings with the placeholder,
     * merging would create a debt from a person to themselves. Refuse instead of guessing.
     */
    private void requireNoSharedHistory(long actorId, long placeholderId) {
        Integer clashes = jdbc.sql("""
                SELECT (SELECT count(*) FROM debts
                         WHERE (debtor_id = :me AND creditor_id = :ph) OR (debtor_id = :ph AND creditor_id = :me))
                     + (SELECT count(*) FROM payments
                         WHERE (from_user_id = :me AND to_user_id = :ph) OR (from_user_id = :ph AND to_user_id = :me))
                     + (SELECT count(*) FROM expense_payers a JOIN expense_payers b ON a.expense_id = b.expense_id
                         WHERE a.user_id = :me AND b.user_id = :ph)
                     + (SELECT count(*) FROM expense_shares a JOIN expense_shares b ON a.expense_id = b.expense_id
                         WHERE a.user_id = :me AND b.user_id = :ph)
                """).param("me", actorId).param("ph", placeholderId).query(Integer.class).single();
        if (clashes > 0) {
            throw ApiException.conflict("claim_conflict",
                    "You already share expenses or debts with this person, so it cannot be you");
        }
    }

    private void repoint(String table, String column, long from, long to) {
        // Table and column names are constants from this class, never user input.
        jdbc.sql("UPDATE " + table + " SET " + column + " = :to WHERE " + column + " = :from")
                .param("to", to).param("from", from).update();
    }
}
