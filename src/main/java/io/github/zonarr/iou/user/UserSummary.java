package io.github.zonarr.iou.user;

/** The public face of a person: safe to show to anyone who shares a group or a debt with them. */
public record UserSummary(Long id, String displayName, boolean placeholder) {

    public static UserSummary of(User user) {
        return new UserSummary(user.getId(), user.getDisplayName(), user.isPlaceholder());
    }
}
