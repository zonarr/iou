package io.github.zonarr.iou.money;

import static io.github.zonarr.iou.money.MoneyTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.money.SplitCalculator.Breakdown;
import io.github.zonarr.iou.money.SplitCalculator.DebtLine;
import io.github.zonarr.iou.money.SplitCalculator.Participant;
import io.github.zonarr.iou.money.SplitCalculator.Payer;
import io.github.zonarr.iou.money.SplitCalculator.PayerLine;
import io.github.zonarr.iou.money.SplitCalculator.ShareLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SplitCalculatorTest {

    private static final long ALICE = 1, BOB = 2, CAROL = 3, DAVE = 4;

    // ---- equal split, one payer ----

    @Test
    void readmeExample_alicePays900ForThree() {
        Breakdown b = SplitCalculator.equalSplit(thb("900"), ALICE, List.of(ALICE, BOB, CAROL));

        assertThat(b.debts()).containsExactly(
                new DebtLine(BOB, ALICE, bd("300.00"), bd("300.00")),
                new DebtLine(CAROL, ALICE, bd("300.00"), bd("300.00")));
        assertThat(b.shares()).extracting(ShareLine::original).containsExactly(bd("300.00"), bd("300.00"), bd("300.00"));
    }

    @Test
    void amountThatDoesNotDivideEvenly_extraSatangGoesToTheLowestId() {
        Breakdown b = SplitCalculator.equalSplit(thb("100"), ALICE, List.of(CAROL, ALICE, BOB));

        // Order of the request does not matter: shares follow id order.
        assertThat(b.shares()).containsExactly(
                new ShareLine(ALICE, bd("33.34"), bd("33.34"), null),
                new ShareLine(BOB, bd("33.33"), bd("33.33"), null),
                new ShareLine(CAROL, bd("33.33"), bd("33.33"), null));
        assertThat(b.debts()).containsExactly(
                new DebtLine(BOB, ALICE, bd("33.33"), bd("33.33")),
                new DebtLine(CAROL, ALICE, bd("33.33"), bd("33.33")));
    }

    @Test
    void whenThePayerHasTheHighestId_othersCarryTheExtraSatang() {
        Breakdown b = SplitCalculator.equalSplit(thb("100"), CAROL, List.of(ALICE, BOB, CAROL));

        assertThat(b.debts()).containsExactly(
                new DebtLine(ALICE, CAROL, bd("33.34"), bd("33.34")),
                new DebtLine(BOB, CAROL, bd("33.33"), bd("33.33")));
    }

    @Test
    void payerWhoIsNotAParticipantIsOwedTheWholeAmount() {
        Breakdown b = SplitCalculator.equalSplit(thb("100.01"), ALICE, List.of(BOB, CAROL));

        assertThat(b.debts()).containsExactly(
                new DebtLine(BOB, ALICE, bd("50.01"), bd("50.01")),
                new DebtLine(CAROL, ALICE, bd("50.00"), bd("50.00")));
        assertThat(b.payers()).containsExactly(new PayerLine(ALICE, bd("100.01"), bd("100.01")));
    }

    @Test
    void oneOnOne_twoPeopleSplitABill() {
        Breakdown b = SplitCalculator.equalSplit(thb("75.25"), BOB, List.of(ALICE, BOB));

        assertThat(b.debts()).containsExactly(new DebtLine(ALICE, BOB, bd("37.63"), bd("37.63")));
    }

    @Test
    void oneSatangBetweenThree() {
        Breakdown b = SplitCalculator.equalSplit(thb("0.01"), CAROL, List.of(ALICE, BOB, CAROL));

        assertThat(b.shares()).extracting(ShareLine::original).containsExactly(bd("0.01"), bd("0.00"), bd("0.00"));
        assertThat(b.debts()).containsExactly(new DebtLine(ALICE, CAROL, bd("0.01"), bd("0.01")));
    }

    @Test
    void payingOnlyForYourselfCreatesNoDebt_soItIsRefused() {
        assertCode(() -> SplitCalculator.equalSplit(thb("100"), ALICE, List.of(ALICE)), "nothing_to_split");
    }

    // ---- multi-currency ----

    @Test
    void foreignCurrency_sharesAddUpInBothCurrencies() {
        // EUR 100.00 at 38.50 = THB 3850.00, three people, Alice paid.
        Breakdown b = SplitCalculator.equalSplit(money("100", "EUR", "38.5"), ALICE, List.of(ALICE, BOB, CAROL));

        assertThat(b.total().thbAmount()).isEqualTo(bd("3850.00"));
        // EUR: 33.34 + 33.33 + 33.33.  THB in proportion: 1283.59 + 1283.205 + 1283.205;
        // the two halves cannot both round up, so the earlier one gets the satang.
        assertThat(b.shares()).containsExactly(
                new ShareLine(ALICE, bd("33.34"), bd("1283.59"), null),
                new ShareLine(BOB, bd("33.33"), bd("1283.21"), null),
                new ShareLine(CAROL, bd("33.33"), bd("1283.20"), null));
        assertThat(b.debts()).containsExactly(
                new DebtLine(BOB, ALICE, bd("33.33"), bd("1283.21")),
                new DebtLine(CAROL, ALICE, bd("33.33"), bd("1283.20")));
        assertThat(b.payers()).containsExactly(new PayerLine(ALICE, bd("100.00"), bd("3850.00")));
    }

    @Test
    void foreignCurrency_awkwardRate() {
        // CHF 47.35 at 43.2178 = 2046.36283 -> THB 2046.36, four people, Dave paid.
        Breakdown b = SplitCalculator.equalSplit(money("47.35", "CHF", "43.2178"), DAVE,
                List.of(ALICE, BOB, CAROL, DAVE));

        assertThat(b.total().thbAmount()).isEqualTo(bd("2046.36"));
        assertThat(b.shares()).extracting(ShareLine::original)
                .containsExactly(bd("11.84"), bd("11.84"), bd("11.84"), bd("11.83"));
        assertThat(sum(b.shares().stream().map(ShareLine::thb).toList())).isEqualTo(bd("2046.36"));
        assertThat(b.debts()).hasSize(3).allSatisfy(d -> {
            assertThat(d.creditorId()).isEqualTo(DAVE);
            assertThat(d.original()).isEqualTo(bd("11.84"));
            // Each debt is within one satang of its own direct conversion.
            assertThat(d.thb().subtract(Money.toThb(d.original(), bd("43.2178"))).abs())
                    .isLessThanOrEqualTo(bd("0.01"));
        });
    }

    // ---- custom splits ----

    @Test
    void exactAmounts() {
        Breakdown b = SplitCalculator.calculate(thb("100"), SplitType.EXACT, paidBy(ALICE, "100"),
                List.of(p(ALICE, "20"), p(BOB, "30.50"), p(CAROL, "49.50")));

        assertThat(b.debts()).containsExactly(
                new DebtLine(BOB, ALICE, bd("30.50"), bd("30.50")),
                new DebtLine(CAROL, ALICE, bd("49.50"), bd("49.50")));
        assertThat(b.shares()).extracting(ShareLine::weight).containsOnlyNulls();
    }

    @Test
    void exactAmountsMustAddUpToTheTotal() {
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.EXACT, paidBy(ALICE, "100"),
                List.of(p(ALICE, "20"), p(BOB, "30"))), "split_mismatch");
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.EXACT, paidBy(ALICE, "100"),
                List.of(p(ALICE, "100"), p(BOB, "0"))), "amount_not_positive");
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.EXACT, paidBy(ALICE, "100"),
                List.of(p(ALICE, "50"), new Participant(BOB, null))), "amount_required");
    }

    @Test
    void percentages() {
        Breakdown b = SplitCalculator.calculate(thb("99.99"), SplitType.PERCENT, paidBy(ALICE, "99.99"),
                List.of(p(ALICE, "50"), p(BOB, "30"), p(CAROL, "20")));

        assertThat(b.shares()).containsExactly(
                new ShareLine(ALICE, bd("49.99"), bd("49.99"), bd("50")),
                new ShareLine(BOB, bd("30.00"), bd("30.00"), bd("30")),
                new ShareLine(CAROL, bd("20.00"), bd("20.00"), bd("20")));
        assertThat(b.debts()).containsExactly(
                new DebtLine(BOB, ALICE, bd("30.00"), bd("30.00")),
                new DebtLine(CAROL, ALICE, bd("20.00"), bd("20.00")));
    }

    @Test
    void percentagesMustAddUpTo100() {
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.PERCENT, paidBy(ALICE, "100"),
                List.of(p(ALICE, "50"), p(BOB, "49.99"))), "percent_mismatch");
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.PERCENT, paidBy(ALICE, "100"),
                List.of(p(ALICE, "100"), p(BOB, "0"))), "split_value_required");
        // Thirds written as 33.3333 + 33.3333 + 33.3334 are accepted.
        Breakdown thirds = SplitCalculator.calculate(thb("100"), SplitType.PERCENT, paidBy(ALICE, "100"),
                List.of(p(ALICE, "33.3333"), p(BOB, "33.3333"), p(CAROL, "33.3334")));
        assertThat(sum(thirds.shares().stream().map(ShareLine::original).toList())).isEqualTo(bd("100.00"));
    }

    @Test
    void shares_twoAdultsCountDoubleAChild() {
        Breakdown b = SplitCalculator.calculate(money("90", "EUR", "40"), SplitType.SHARES, paidBy(ALICE, "90"),
                List.of(p(ALICE, "2"), p(BOB, "2"), p(CAROL, "1")));

        assertThat(b.shares()).containsExactly(
                new ShareLine(ALICE, bd("36.00"), bd("1440.00"), bd("2")),
                new ShareLine(BOB, bd("36.00"), bd("1440.00"), bd("2")),
                new ShareLine(CAROL, bd("18.00"), bd("720.00"), bd("1")));
    }

    @Test
    void sharesRejectBadNumbers() {
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.SHARES, paidBy(ALICE, "100"),
                List.of(p(ALICE, "1"), p(BOB, "-1"))), "split_value_required");
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.SHARES, paidBy(ALICE, "100"),
                List.of(p(ALICE, "1"), p(BOB, "1.00001"))), "split_value_invalid");
    }

    // ---- several payers ----

    @Test
    void twoPayers_debtsGoToWhoeverPaidMoreThanTheirShare() {
        // Total 100, equal between three (33.34 / 33.33 / 33.33). Alice paid 60, Bob paid 40.
        // Net: Alice +26.66, Bob +6.67, Carol -33.33.
        Breakdown b = SplitCalculator.calculate(thb("100"), SplitType.EQUAL,
                List.of(new Payer(ALICE, bd("60")), new Payer(BOB, bd("40"))),
                List.of(p(ALICE, null), p(BOB, null), p(CAROL, null)));

        assertThat(b.debts()).containsExactly(
                new DebtLine(CAROL, ALICE, bd("26.66"), bd("26.66")),
                new DebtLine(CAROL, BOB, bd("6.67"), bd("6.67")));
        assertThat(b.payers()).containsExactly(
                new PayerLine(ALICE, bd("60.00"), bd("60.00")),
                new PayerLine(BOB, bd("40.00"), bd("40.00")));
    }

    @Test
    void aPayerWhoPaidLessThanTheirShareStillOwes() {
        // Total 90 between three (30 each). Alice paid 80, Bob paid 10. Bob owes 20, Carol owes 30.
        Breakdown b = SplitCalculator.calculate(thb("90"), SplitType.EQUAL,
                List.of(new Payer(ALICE, bd("80")), new Payer(BOB, bd("10"))),
                List.of(p(ALICE, null), p(BOB, null), p(CAROL, null)));

        assertThat(b.debts()).containsExactly(
                new DebtLine(BOB, ALICE, bd("20.00"), bd("20.00")),
                new DebtLine(CAROL, ALICE, bd("30.00"), bd("30.00")));
    }

    @Test
    void payersMustAddUpToTheTotalAndBeDistinct() {
        List<Participant> everyone = List.of(p(ALICE, null), p(BOB, null));
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.EQUAL,
                List.of(new Payer(ALICE, bd("60")), new Payer(BOB, bd("30"))), everyone), "payers_mismatch");
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.EQUAL,
                List.of(new Payer(ALICE, bd("60")), new Payer(ALICE, bd("40"))), everyone), "duplicate_payer");
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.EQUAL, List.of(), everyone), "payers_required");
        assertCode(() -> SplitCalculator.calculate(thb("100"), SplitType.EQUAL,
                List.of(new Payer(ALICE, bd("100")), new Payer(BOB, bd("0"))), everyone), "amount_not_positive");
    }

    @Test
    void participantsMustBeGivenAndDistinct() {
        assertCode(() -> SplitCalculator.equalSplit(thb("100"), ALICE, List.of()), "participants_required");
        assertCode(() -> SplitCalculator.equalSplit(thb("100"), ALICE, List.of(BOB, BOB)), "duplicate_participant");
        assertCode(() -> SplitCalculator.calculate(thb("100"), null, paidBy(ALICE, "100"), List.of(p(BOB, null))),
                "split_type_required");
    }

    // ---- invariants over many random expenses ----

    @Test
    void everyRandomExpenseBalancesToTheSatangInBothCurrencies() {
        Random random = new Random(1612);
        String[] currencies = {"THB", "EUR", "CHF", "JPY"};
        String[] rates = {"1", "38.4567", "43.21098765", "0.2345"};
        for (int round = 0; round < 3000; round++) {
            int c = random.nextInt(currencies.length);
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(2_000_000), 2);
            MoneyAmount total = Money.convert(amount, currencies[c], c == 0 ? null : bd(rates[c]));

            int people = 2 + random.nextInt(11);
            SplitType type = SplitType.values()[random.nextInt(SplitType.values().length)];
            List<Participant> participants = randomParticipants(random, type, amount, people);
            List<Payer> payers = randomPayers(random, amount, people);

            Breakdown b;
            try {
                b = SplitCalculator.calculate(total, type, payers, participants);
            } catch (MoneyException e) {
                assertThat(e.code()).isEqualTo("nothing_to_split"); // payers happened to be the only participants
                continue;
            }

            String label = "round " + round + " " + type + " " + total;
            assertThat(sum(b.shares().stream().map(ShareLine::original).toList())).as(label).isEqualTo(amount);
            assertThat(sum(b.shares().stream().map(ShareLine::thb).toList())).as(label).isEqualTo(total.thbAmount());
            assertThat(sum(b.payers().stream().map(PayerLine::original).toList())).as(label).isEqualTo(amount);
            assertThat(sum(b.payers().stream().map(PayerLine::thb).toList())).as(label).isEqualTo(total.thbAmount());

            // For every person: paid - share == what they are owed - what they owe, in both currencies.
            Map<Long, BigDecimal> netOriginal = new HashMap<>();
            Map<Long, BigDecimal> netThb = new HashMap<>();
            b.payers().forEach(p -> {
                netOriginal.merge(p.userId(), p.original(), BigDecimal::add);
                netThb.merge(p.userId(), p.thb(), BigDecimal::add);
            });
            b.shares().forEach(s -> {
                netOriginal.merge(s.userId(), s.original().negate(), BigDecimal::add);
                netThb.merge(s.userId(), s.thb().negate(), BigDecimal::add);
            });
            for (DebtLine d : b.debts()) {
                assertThat(d.original().signum()).as(label).isPositive();
                assertThat(d.thb().signum()).as(label).isGreaterThanOrEqualTo(0);
                assertThat(d.debtorId()).isNotEqualTo(d.creditorId());
                netOriginal.merge(d.creditorId(), d.original().negate(), BigDecimal::add);
                netOriginal.merge(d.debtorId(), d.original(), BigDecimal::add);
                netThb.merge(d.creditorId(), d.thb().negate(), BigDecimal::add);
                netThb.merge(d.debtorId(), d.thb(), BigDecimal::add);
                // No debt drifts more than a satang from its own direct conversion.
                assertThat(d.thb().subtract(Money.toThb(d.original(), total.exchangeRate())).abs())
                        .as(label).isLessThanOrEqualTo(bd("0.01"));
            }
            assertThat(netOriginal.values()).as(label).allSatisfy(v -> assertThat(v.signum()).isZero());
            assertThat(netThb.values()).as(label).allSatisfy(v -> assertThat(v.signum()).isZero());
            assertThat(b.debts().size()).as(label).isLessThan(people + 1);
            assertThat(SplitCalculator.calculate(total, type, payers, participants)).isEqualTo(b); // deterministic
        }
    }

    private static List<Participant> randomParticipants(Random random, SplitType type, BigDecimal amount, int people) {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= people; id++) {
            if (random.nextInt(4) != 0) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            ids.add(1L);
        }
        List<Participant> participants = new ArrayList<>();
        switch (type) {
            case EQUAL -> ids.forEach(id -> participants.add(new Participant(id, null)));
            case SHARES -> ids.forEach(id -> participants.add(
                    new Participant(id, BigDecimal.valueOf(1 + random.nextInt(50), random.nextInt(2)))));
            case PERCENT -> {
                // Random percentages with 2 decimals that add up to exactly 100.
                List<BigDecimal> cut = cut(random, new BigDecimal("100.00"), ids.size());
                for (int i = 0; i < ids.size(); i++) {
                    participants.add(new Participant(ids.get(i), cut.get(i)));
                }
            }
            case EXACT -> {
                long satang = amount.unscaledValue().longValueExact();
                if (satang < ids.size()) {
                    ids.subList((int) satang, ids.size()).clear();
                }
                List<BigDecimal> cut = cut(random, amount, ids.size());
                for (int i = 0; i < ids.size(); i++) {
                    participants.add(new Participant(ids.get(i), cut.get(i)));
                }
            }
        }
        return participants;
    }

    private static List<Payer> randomPayers(Random random, BigDecimal amount, int people) {
        int count = (int) Math.min(1 + random.nextInt(3), amount.unscaledValue().longValueExact());
        List<Long> ids = new ArrayList<>();
        while (ids.size() < Math.min(count, people)) {
            long id = 1 + random.nextInt(people);
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        List<BigDecimal> cut = cut(random, amount, ids.size());
        List<Payer> payers = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            payers.add(new Payer(ids.get(i), cut.get(i)));
        }
        return payers;
    }

    /** Cuts {@code total} into {@code parts} random positive amounts (2 decimals) that add up exactly. */
    private static List<BigDecimal> cut(Random random, BigDecimal total, int parts) {
        long remaining = total.unscaledValue().longValueExact();
        List<BigDecimal> result = new ArrayList<>();
        for (int i = parts; i > 1; i--) {
            long max = remaining - (i - 1);
            long piece = 1 + (max <= 1 ? 0 : (long) (random.nextDouble() * (max - 1)));
            result.add(BigDecimal.valueOf(piece, 2));
            remaining -= piece;
        }
        result.add(BigDecimal.valueOf(remaining, 2));
        return result;
    }

    static MoneyAmount thb(String amount) {
        return Money.convert(bd(amount), "THB", null);
    }

    static MoneyAmount money(String amount, String currency, String rate) {
        return Money.convert(bd(amount), currency, bd(rate));
    }

    static List<Payer> paidBy(long userId, String amount) {
        return List.of(new Payer(userId, bd(amount)));
    }

    static Participant p(long userId, String value) {
        return new Participant(userId, value == null ? null : bd(value));
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
