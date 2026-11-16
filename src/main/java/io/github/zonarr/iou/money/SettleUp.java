package io.github.zonarr.iou.money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Suggests who should pay whom so that everybody ends at zero, using few payments.
 *
 * <p>Greedy method from the README: repeatedly match the person who owes the most with the
 * person who is owed the most, and transfer the smaller of the two amounts. Each transfer
 * settles at least one person completely, so n people need at most n - 1 payments.
 *
 * <p>Ties are broken by the lower id, so the same balances always give the same plan.
 */
public final class SettleUp {

    private SettleUp() {
    }

    /** One suggested payment. */
    public record Transfer(long fromId, long toId, BigDecimal amount) {
    }

    private record Entry(long id, BigDecimal amount) {
    }

    /**
     * @param netBalances per person: positive = is owed money, negative = owes money.
     *                    Must add up to zero (it always does when built from debts and payments).
     * @return the suggested payments; empty if everybody is already settled
     */
    public static List<Transfer> plan(Map<Long, BigDecimal> netBalances) {
        Comparator<Entry> largestFirst = Comparator.comparing(Entry::amount).reversed()
                .thenComparing(Entry::id);
        PriorityQueue<Entry> debtors = new PriorityQueue<>(largestFirst);
        PriorityQueue<Entry> creditors = new PriorityQueue<>(largestFirst);

        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> balance : netBalances.entrySet()) {
            BigDecimal amount = balance.getValue();
            sum = sum.add(amount);
            if (amount.signum() > 0) {
                creditors.add(new Entry(balance.getKey(), amount));
            } else if (amount.signum() < 0) {
                debtors.add(new Entry(balance.getKey(), amount.negate()));
            }
        }
        if (sum.signum() != 0) {
            throw new IllegalArgumentException("Net balances must add up to zero, but add up to " + sum);
        }

        List<Transfer> transfers = new ArrayList<>();
        while (!debtors.isEmpty()) {
            Entry debtor = debtors.poll();
            Entry creditor = creditors.poll();
            BigDecimal amount = debtor.amount().min(creditor.amount());
            transfers.add(new Transfer(debtor.id(), creditor.id(), amount));
            if (debtor.amount().compareTo(amount) > 0) {
                debtors.add(new Entry(debtor.id(), debtor.amount().subtract(amount)));
            }
            if (creditor.amount().compareTo(amount) > 0) {
                creditors.add(new Entry(creditor.id(), creditor.amount().subtract(amount)));
            }
        }
        return transfers;
    }
}
