package io.github.zonarr.iou.ledger;

public enum ConfirmationStatus {
    /** Waiting for the debtor (debts) or the receiver (payments). Not counted in balances. */
    PENDING,
    /** Agreed. Counted in balances and settle-up. */
    CONFIRMED,
    /** Refused. Never counted. */
    REJECTED
}
