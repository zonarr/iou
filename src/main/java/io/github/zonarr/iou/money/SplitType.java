package io.github.zonarr.iou.money;

/** How an expense's total is divided among the people it was for. */
public enum SplitType {
    /** Everybody the same. */
    EQUAL,
    /** Each person's amount is typed in; they must add up to the total. */
    EXACT,
    /** Each person's percentage is typed in; they must add up to 100. */
    PERCENT,
    /** Relative shares, e.g. 2 : 1 : 1. */
    SHARES
}
