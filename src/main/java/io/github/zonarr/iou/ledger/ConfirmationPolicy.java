package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.user.User;

/**
 * Decides whether something just recorded or edited counts straight away.
 *
 * <p>Nobody should get a debt put on their balance without agreeing to it. The person whose
 * agreement is needed (the "approver") is the debtor of a debt, or the receiver of a payment.
 * The entry is confirmed immediately when:
 * <ul>
 *   <li>the approver recorded it themselves, or</li>
 *   <li>the approver is a placeholder, who cannot respond, or</li>
 *   <li>the group is a "trusted group" (auto-confirm switched on by its owner).</li>
 * </ul>
 * Otherwise it is pending until the approver accepts.
 */
public final class ConfirmationPolicy {

    private ConfirmationPolicy() {
    }

    public static ConfirmationStatus statusFor(long actorId, User approver, Group group) {
        if (approver.isPlaceholder()
                || approver.getId() == actorId
                || (group != null && group.isAutoConfirm())) {
            return ConfirmationStatus.CONFIRMED;
        }
        return ConfirmationStatus.PENDING;
    }
}
