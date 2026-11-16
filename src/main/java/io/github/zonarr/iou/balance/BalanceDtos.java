package io.github.zonarr.iou.balance;

import io.github.zonarr.iou.user.UserSummary;
import java.math.BigDecimal;
import java.util.List;

public final class BalanceDtos {

    private BalanceDtos() {
    }

    /** @param net positive: the group owes this person; negative: this person owes the group. THB. */
    public record MemberBalance(UserSummary user, BigDecimal net) {
    }

    /**
     * @param pendingDebts    debts in the group still waiting for an answer (not counted in {@code balances})
     * @param pendingPayments payments still waiting to be confirmed (not counted either)
     */
    public record GroupBalances(Long groupId, String currency, List<MemberBalance> balances, int pendingDebts,
            int pendingPayments) {
    }

    /** A suggested payment. Advisory only: nothing changes until a payment is recorded and confirmed. */
    public record Transfer(UserSummary from, UserSummary to, BigDecimal amount) {
    }

    public record GroupSettleUp(Long groupId, String currency, List<Transfer> transfers) {
    }

    /** @param groupId null for "outside any group" */
    public record ContextBalance(Long groupId, String groupName, BigDecimal net) {
    }

    /** A suggested payment that involves me, and the group it settles (null = outside groups). */
    public record MyTransfer(Long groupId, String groupName, UserSummary from, UserSummary to, BigDecimal amount) {
    }

    /** @param net positive: this person should pay me in total; negative: I should pay them */
    public record PersonNet(UserSummary user, BigDecimal net) {
    }

    /**
     * Everything I owe and am owed, across all my groups and one-on-one debts.
     *
     * @param totalOwedByMe sum of my suggested payments to others
     * @param totalOwedToMe sum of the suggested payments to me
     * @param contexts      my net balance in each group and outside groups
     * @param transfers     the suggested payments that involve me, one context at a time
     * @param byPerson      the same payments added up per person
     */
    public record MySettleUp(String currency, BigDecimal totalOwedByMe, BigDecimal totalOwedToMe, BigDecimal net,
            List<ContextBalance> contexts, List<MyTransfer> transfers, List<PersonNet> byPerson) {
    }
}
