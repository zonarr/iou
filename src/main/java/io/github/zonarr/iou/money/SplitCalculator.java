package io.github.zonarr.iou.money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Works out, for one expense, each person's share and the debts that follow from it.
 *
 * <ol>
 *   <li><b>Shares</b> are calculated in the currency the expense was paid in, so they add up
 *       to exactly what was paid. People are taken in id order; leftover cents go to the
 *       first ones (see {@link Allocator}).</li>
 *   <li><b>Debts</b>: everyone's net position is what they paid minus their share. People who
 *       paid less than their share owe people who paid more, matched with {@link SettleUp}.
 *       With a single payer that is simply one debt per other participant.</li>
 *   <li><b>THB</b>: the expense total is converted once (amount x rate, half-up). That THB
 *       total is then distributed over the debts and the "paid for myself" parts in
 *       proportion to their original amounts, so the THB figures also add up exactly.</li>
 * </ol>
 */
public final class SplitCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_WEIGHT = new BigDecimal("1000000");
    private static final int WEIGHT_SCALE = 4;

    private SplitCalculator() {
    }

    /** @param value EXACT: the amount; PERCENT: the percentage; SHARES: the share count; EQUAL: ignored */
    public record Participant(long userId, BigDecimal value) {
    }

    public record Payer(long userId, BigDecimal amount) {
    }

    /** @param weight the percentage or share count as typed (null for EQUAL and EXACT) */
    public record ShareLine(long userId, BigDecimal original, BigDecimal thb, BigDecimal weight) {
    }

    public record PayerLine(long userId, BigDecimal original, BigDecimal thb) {
    }

    public record DebtLine(long debtorId, long creditorId, BigDecimal original, BigDecimal thb) {
    }

    public record Breakdown(MoneyAmount total, SplitType splitType, List<PayerLine> payers,
            List<ShareLine> shares, List<DebtLine> debts) {
    }

    /** Part of the total that {@code funderId} paid on behalf of {@code consumerId} (may be the same person). */
    private record Flow(long consumerId, long funderId, BigDecimal original) {
    }

    /** The common case: one person paid everything and it is split equally. */
    public static Breakdown equalSplit(MoneyAmount total, long payerId, List<Long> participantIds) {
        return calculate(total, SplitType.EQUAL,
                List.of(new Payer(payerId, total.originalAmount())),
                participantIds.stream().map(id -> new Participant(id, null)).toList());
    }

    /**
     * @param total        the expense total, already validated and converted
     * @param payers       who paid and how much each, in the expense's currency; must add up to the total
     * @param participants who the expense was for
     * @throws MoneyException if the inputs do not describe a valid split
     */
    public static Breakdown calculate(MoneyAmount total, SplitType splitType, List<Payer> payers,
            List<Participant> participants) {
        if (splitType == null) {
            throw new MoneyException("split_type_required", "A split type is required");
        }
        List<Participant> people = sortedParticipants(participants);
        TreeMap<Long, BigDecimal> paid = paidByPerson(total, payers);
        List<BigDecimal> shareAmounts = shareAmounts(total.originalAmount(), splitType, people);

        // Net position in the expense's currency: paid minus share.
        TreeMap<Long, BigDecimal> share = new TreeMap<>();
        for (int i = 0; i < people.size(); i++) {
            share.put(people.get(i).userId(), shareAmounts.get(i));
        }
        TreeMap<Long, BigDecimal> net = new TreeMap<>(paid);
        share.forEach((id, amount) -> net.merge(id, amount.negate(), BigDecimal::add));
        List<SettleUp.Transfer> transfers = SettleUp.plan(net);
        if (transfers.isEmpty()) {
            throw new MoneyException("nothing_to_split",
                    "Nobody would owe anything: the people who paid are the only ones it was for");
        }

        // Every satang of the total is either a debt or something a payer spent on themselves.
        List<Flow> flows = new ArrayList<>();
        for (SettleUp.Transfer t : transfers) {
            flows.add(new Flow(t.fromId(), t.toId(), t.amount()));
        }
        for (Map.Entry<Long, BigDecimal> p : paid.entrySet()) {
            BigDecimal own = p.getValue().min(share.getOrDefault(p.getKey(), Money.ZERO));
            if (own.signum() > 0) {
                flows.add(new Flow(p.getKey(), p.getKey(), own));
            }
        }
        flows.sort(Comparator.comparingLong(Flow::consumerId).thenComparingLong(Flow::funderId));
        List<BigDecimal> flowThb = Allocator.allocate(total.thbAmount(),
                flows.stream().map(Flow::original).toList());

        TreeMap<Long, BigDecimal> shareThb = new TreeMap<>();
        TreeMap<Long, BigDecimal> paidThb = new TreeMap<>();
        List<DebtLine> debts = new ArrayList<>();
        for (int i = 0; i < flows.size(); i++) {
            Flow flow = flows.get(i);
            BigDecimal thb = flowThb.get(i);
            shareThb.merge(flow.consumerId(), thb, BigDecimal::add);
            paidThb.merge(flow.funderId(), thb, BigDecimal::add);
            if (flow.consumerId() != flow.funderId()) {
                debts.add(new DebtLine(flow.consumerId(), flow.funderId(), flow.original(), thb));
            }
        }

        List<ShareLine> shareLines = new ArrayList<>();
        for (Participant person : people) {
            BigDecimal weight = splitType == SplitType.PERCENT || splitType == SplitType.SHARES
                    ? person.value() : null;
            shareLines.add(new ShareLine(person.userId(), share.get(person.userId()),
                    shareThb.getOrDefault(person.userId(), Money.ZERO), weight));
        }
        List<PayerLine> payerLines = new ArrayList<>();
        paid.forEach((id, amount) ->
                payerLines.add(new PayerLine(id, amount, paidThb.getOrDefault(id, Money.ZERO))));
        return new Breakdown(total, splitType, payerLines, shareLines, debts);
    }

    private static List<Participant> sortedParticipants(List<Participant> participants) {
        if (participants == null || participants.isEmpty()) {
            throw new MoneyException("participants_required", "Choose at least one person to split between");
        }
        Set<Long> seen = new HashSet<>();
        for (Participant p : participants) {
            if (!seen.add(p.userId())) {
                throw new MoneyException("duplicate_participant", "A person is listed twice in the split");
            }
        }
        return participants.stream().sorted(Comparator.comparingLong(Participant::userId)).toList();
    }

    private static TreeMap<Long, BigDecimal> paidByPerson(MoneyAmount total, List<Payer> payers) {
        if (payers == null || payers.isEmpty()) {
            throw new MoneyException("payers_required", "Say who paid");
        }
        TreeMap<Long, BigDecimal> paid = new TreeMap<>();
        BigDecimal sum = Money.ZERO;
        for (Payer payer : payers) {
            BigDecimal amount = Money.requireAmount(payer.amount());
            if (paid.put(payer.userId(), amount) != null) {
                throw new MoneyException("duplicate_payer", "A person is listed twice as payer");
            }
            sum = sum.add(amount);
        }
        if (sum.compareTo(total.originalAmount()) != 0) {
            throw new MoneyException("payers_mismatch",
                    "The amounts paid add up to " + sum + ", not the total " + total.originalAmount());
        }
        return paid;
    }

    private static List<BigDecimal> shareAmounts(BigDecimal total, SplitType type, List<Participant> people) {
        switch (type) {
            case EQUAL:
                return Allocator.splitEqually(total, people.size());
            case EXACT: {
                List<BigDecimal> amounts = new ArrayList<>();
                BigDecimal sum = Money.ZERO;
                for (Participant p : people) {
                    BigDecimal amount = Money.requireAmount(p.value());
                    amounts.add(amount);
                    sum = sum.add(amount);
                }
                if (sum.compareTo(total) != 0) {
                    throw new MoneyException("split_mismatch",
                            "The amounts add up to " + sum + ", not the total " + total);
                }
                return amounts;
            }
            case PERCENT: {
                List<BigDecimal> percents = weights(people);
                BigDecimal sum = percents.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                if (sum.compareTo(HUNDRED) != 0) {
                    throw new MoneyException("percent_mismatch",
                            "The percentages add up to " + sum.stripTrailingZeros().toPlainString() + ", not 100");
                }
                return Allocator.allocate(total, percents);
            }
            case SHARES:
                return Allocator.allocate(total, weights(people));
            default:
                throw new IllegalStateException("Unhandled split type " + type);
        }
    }

    private static List<BigDecimal> weights(List<Participant> people) {
        List<BigDecimal> weights = new ArrayList<>();
        for (Participant p : people) {
            BigDecimal value = p.value();
            if (value == null || value.signum() <= 0) {
                throw new MoneyException("split_value_required",
                        "Every person in the split needs a number greater than zero");
            }
            if (value.stripTrailingZeros().scale() > WEIGHT_SCALE || value.compareTo(MAX_WEIGHT) > 0) {
                throw new MoneyException("split_value_invalid",
                        "Percentages and shares can have at most 4 decimal places");
            }
            weights.add(value);
        }
        return weights;
    }
}
