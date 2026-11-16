package io.github.zonarr.iou.audit;

public enum AuditAction {
    CREATED,
    UPDATED,
    DELETED,
    /** Debtor agreed to a pending debt. */
    ACCEPTED,
    /** Debtor refused a debt, or receiver refused a payment. */
    REJECTED,
    /** Receiver confirmed a payment arrived. */
    CONFIRMED,
    /** Member events. */
    ADDED,
    JOINED,
    REMOVED,
    RENAMED,
    /** A placeholder was taken over by a real account. */
    CLAIMED,
    /** The group's invite link was replaced. */
    INVITE_RESET
}
