package io.github.zonarr.iou.group;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.zonarr.iou.auth.Actor;
import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.GroupDtos.AddMemberRequest;
import io.github.zonarr.iou.group.GroupDtos.ClaimRequest;
import io.github.zonarr.iou.group.GroupDtos.CreateGroupRequest;
import io.github.zonarr.iou.group.GroupDtos.GroupDetail;
import io.github.zonarr.iou.group.GroupDtos.GroupSummary;
import io.github.zonarr.iou.group.GroupDtos.InvitePreview;
import io.github.zonarr.iou.group.GroupDtos.JoinRequest;
import io.github.zonarr.iou.group.GroupDtos.RenameMemberRequest;
import io.github.zonarr.iou.group.GroupDtos.UpdateGroupRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Groups", description = "Groups, members, placeholders and invite links")
@RestController
@RequestMapping("/api")
public class GroupController {

    private final GroupService groups;
    private final ClaimService claims;

    public GroupController(GroupService groups, ClaimService claims) {
        this.groups = groups;
        this.claims = claims;
    }

    @Operation(summary = "Create a group")
    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupDetail create(Actor actor, @Valid @RequestBody CreateGroupRequest request) {
        return groups.create(actor.id(), request.name());
    }

    @Operation(summary = "My groups")
    @GetMapping("/groups")
    public List<GroupSummary> list(Actor actor) {
        return groups.listFor(actor.id());
    }

    @Operation(summary = "One group with its members and invite code")
    @GetMapping("/groups/{id}")
    public GroupDetail get(Actor actor, @PathVariable long id) {
        return groups.get(actor.id(), id);
    }

    /** Owner only: rename the group or switch "trusted group" (auto-confirm) on or off. */
    @Operation(summary = "Rename the group or switch trusted mode (owner only)")
    @PatchMapping("/groups/{id}")
    public GroupDetail update(Actor actor, @PathVariable long id, @Valid @RequestBody UpdateGroupRequest request) {
        return groups.update(actor.id(), id, request.name(), request.autoConfirm());
    }

    /** Owner only: makes the old invite link stop working and returns a new one. */
    @Operation(summary = "Replace the invite link (owner only)")
    @PostMapping("/groups/{id}/invite/reset")
    public GroupDetail resetInvite(Actor actor, @PathVariable long id) {
        return groups.resetInviteCode(actor.id(), id);
    }

    @Operation(summary = "Add a member: a placeholder by name, or a registered user by email")
    @PostMapping("/groups/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupDetail addMember(Actor actor, @PathVariable long id, @Valid @RequestBody AddMemberRequest request) {
        boolean hasName = request.name() != null && !request.name().isBlank();
        boolean hasEmail = request.email() != null && !request.email().isBlank();
        if (hasName == hasEmail) {
            throw ApiException.badRequest("name_or_email",
                    "Give either a name (for someone without an account) or an email, not both");
        }
        return hasName
                ? groups.addPlaceholder(actor.id(), id, request.name())
                : groups.addByEmail(actor.id(), id, request.email());
    }

    /** Renames a placeholder member. */
    @Operation(summary = "Rename a placeholder member")
    @PatchMapping("/groups/{id}/members/{userId}")
    public GroupDetail renameMember(Actor actor, @PathVariable long id, @PathVariable long userId,
            @Valid @RequestBody RenameMemberRequest request) {
        return groups.renamePlaceholder(actor.id(), id, userId, request.name());
    }

    /** Removes a member who has no expenses, debts or payments in the group. */
    @Operation(summary = "Remove a member who has no history in the group (or leave the group)")
    @DeleteMapping("/groups/{id}/members/{userId}")
    public GroupDetail removeMember(Actor actor, @PathVariable long id, @PathVariable long userId) {
        return groups.removeMember(actor.id(), id, userId);
    }

    @Operation(summary = "What an invite link leads to, and which placeholders can be claimed")
    @GetMapping("/invites/{code}")
    public InvitePreview previewInvite(Actor actor, @PathVariable String code) {
        return groups.previewInvite(actor.id(), code);
    }

    /** Joins the group behind an invite link, optionally taking over a placeholder. */
    @Operation(summary = "Join through an invite link, optionally claiming a placeholder")
    @PostMapping("/invites/{code}/join")
    public GroupDetail join(Actor actor, @PathVariable String code, @RequestBody(required = false) JoinRequest request) {
        if (request != null && request.placeholderId() != null) {
            groups.requireInvite(code);
            return claims.claim(actor.id(), request.placeholderId(), code);
        }
        return groups.join(actor.id(), code);
    }

    /** Links a placeholder to the caller's account; its whole history moves over. */
    @Operation(summary = "Link a placeholder to my account; its debt history moves over")
    @PostMapping("/placeholders/{id}/claim")
    public GroupDetail claim(Actor actor, @PathVariable long id, @RequestBody(required = false) ClaimRequest request) {
        return claims.claim(actor.id(), id, request == null ? null : request.inviteCode());
    }
}
