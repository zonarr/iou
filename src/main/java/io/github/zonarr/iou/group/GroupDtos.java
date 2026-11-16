package io.github.zonarr.iou.group;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class GroupDtos {

    private GroupDtos() {
    }

    public record CreateGroupRequest(@NotBlank @Size(max = 80) String name) {
    }

    /** Owner-only settings. Fields left out (null) are not changed. */
    public record UpdateGroupRequest(@Size(min = 1, max = 80) String name, Boolean autoConfirm) {
    }

    /**
     * Give exactly one: {@code name} adds a placeholder (a person with no account yet);
     * {@code email} adds somebody who has already registered.
     */
    public record AddMemberRequest(@Size(max = 80) String name, @Email @Size(max = 254) String email) {
    }

    public record RenameMemberRequest(@NotBlank @Size(max = 80) String name) {
    }

    public record MemberView(Long id, String displayName, boolean placeholder, Instant joinedAt) {
    }

    public record GroupSummary(Long id, String name, Long ownerId, boolean autoConfirm, int memberCount,
            Instant createdAt) {
    }

    public record GroupDetail(Long id, String name, Long ownerId, boolean autoConfirm, String inviteCode,
            List<MemberView> members, Instant createdAt) {
    }

    /** What somebody holding an invite link sees before joining. */
    public record InvitePreview(Long groupId, String groupName, boolean alreadyMember, int memberCount,
            List<MemberView> placeholders) {
    }

    /** {@code placeholderId} is optional: "I am this person" instead of joining as somebody new. */
    public record JoinRequest(Long placeholderId) {
    }

    /** Needed only when the caller is not yet a member of the placeholder's group. */
    public record ClaimRequest(String inviteCode) {
    }
}
