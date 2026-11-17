package io.github.zonarr.iou.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.zonarr.iou.money.SettleUp.Transfer;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SettleUpTest {

    private static final long A = 1, B = 2, C = 3, D = 4;

    @Test
    void readmeExample_onePaymentInsteadOfThree() {
        // A owes B 100, B owes C 100, A owes C 50  ->  A: -150, B: 0, C: +150
        List<Transfer> plan = SettleUp.plan(balances(A, "-150.00", B, "0.00", C, "150.00"));

        assertThat(plan).containsExactly(new Transfer(A, C, bd("150.00")));
    }

    @Test
    void nothingToDoWhenEverybodyIsAtZero() {
        assertThat(SettleUp.plan(Map.of())).isEmpty();
        assertThat(SettleUp.plan(balances(A, "0.00", B, "0.00", C, "0"))).isEmpty();
    }

    @Test
    void twoPeople() {
        assertThat(SettleUp.plan(balances(A, "20.50", B, "-20.50")))
                .containsExactly(new Transfer(B, A, bd("20.50")));
    }

    @Test
    void largestDebtorIsMatchedWithLargestCreditorFirst() {
        // Debtors: A 70, B 30. Creditors: C 60, D 40.
        List<Transfer> plan = SettleUp.plan(balances(A, "-70.00", B, "-30.00", C, "60.00", D, "40.00"));

        assertThat(plan).containsExactly(
                new Transfer(A, C, bd("60.00")),   // A (70) -> C (60): C settled, A still owes 10
                new Transfer(B, D, bd("30.00")),   // largest left: B (30) -> D (40)
                new Transfer(A, D, bd("10.00")));  // A (10) -> D (10)
    }

    @Test
    void oneCreditorManyDebtors() {
        List<Transfer> plan = SettleUp.plan(balances(A, "300.00", B, "-100.00", C, "-150.00", D, "-50.00"));

        assertThat(plan).containsExactly(
                new Transfer(C, A, bd("150.00")),
                new Transfer(B, A, bd("100.00")),
                new Transfer(D, A, bd("50.00")));
    }

    @Test
    void tiesAreBrokenByLowerId_soThePlanIsStable() {
        Map<Long, BigDecimal> shuffled = new LinkedHashMap<>();
        shuffled.put(D, bd("50.00"));
        shuffled.put(B, bd("-50.00"));
        shuffled.put(C, bd("50.00"));
        shuffled.put(A, bd("-50.00"));

        assertThat(SettleUp.plan(shuffled)).containsExactly(
                new Transfer(A, C, bd("50.00")),
                new Transfer(B, D, bd("50.00")));
    }

    @Test
    void handlesSingleSatangBalances() {
        assertThat(SettleUp.plan(balances(A, "0.01", B, "0.01", C, "-0.02"))).containsExactly(
                new Transfer(C, A, bd("0.01")),
                new Transfer(C, B, bd("0.01")));
    }

    @Test
    void balancesThatDoNotSumToZeroAreABug() {
        assertThatThrownBy(() -> SettleUp.plan(balances(A, "10.00", B, "-9.99")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manyMembers_planSettlesEveryoneWithAtMostNMinusOnePayments() {
        Random random = new Random(42);
        for (int round = 0; round < 500; round++) {
            int n = 2 + random.nextInt(40);
            Map<Long, BigDecimal> balances = new HashMap<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (long id = 1; id < n; id++) {
                // Mix of zero balances, debtors and creditors.
                BigDecimal amount = random.nextInt(5) == 0
                        ? bd("0.00") : BigDecimal.valueOf(random.nextInt(2_000_001) - 1_000_000, 2);
                balances.put(id, amount);
                sum = sum.add(amount);
            }
            balances.put((long) n, sum.negate()); // the last person absorbs the rest, so it sums to zero

            List<Transfer> plan = SettleUp.plan(balances);

            long nonZero = balances.values().stream().filter(b -> b.signum() != 0).count();
            assertThat(plan.size()).as("round %d", round).isLessThanOrEqualTo((int) Math.max(nonZero - 1, 0));
            Map<Long, BigDecimal> after = new HashMap<>(balances);
            for (Transfer t : plan) {
                assertThat(t.amount().signum()).isPositive();
                assertThat(t.amount().scale()).isEqualTo(2);
                assertThat(t.fromId()).isNotEqualTo(t.toId());
                // Only debtors pay and only creditors receive.
                assertThat(balances.get(t.fromId()).signum()).isNegative();
                assertThat(balances.get(t.toId()).signum()).isPositive();
                after.merge(t.fromId(), t.amount(), BigDecimal::add);
                after.merge(t.toId(), t.amount().negate(), BigDecimal::add);
            }
            assertThat(after.values()).allSatisfy(b -> assertThat(b.signum()).isZero());
            assertThat(SettleUp.plan(balances)).isEqualTo(plan); // deterministic
        }
    }

    @Test
    void hundredMembersOwingOnePerson() {
        Map<Long, BigDecimal> balances = new HashMap<>();
        for (long id = 2; id <= 101; id++) {
            balances.put(id, bd("-12.34"));
        }
        balances.put(1L, bd("1234.00"));

        List<Transfer> plan = SettleUp.plan(balances);

        assertThat(plan).hasSize(100);
        assertThat(plan).allSatisfy(t -> {
            assertThat(t.toId()).isEqualTo(1L);
            assertThat(t.amount()).isEqualTo(bd("12.34"));
        });
        assertThat(plan.get(0).fromId()).isEqualTo(2L); // equal debts: lowest id first
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    static Map<Long, BigDecimal> balances(Object... idThenAmount) {
        Map<Long, BigDecimal> map = new LinkedHashMap<>();
        for (int i = 0; i < idThenAmount.length; i += 2) {
            map.put((Long) idThenAmount[i], bd((String) idThenAmount[i + 1]));
        }
        return map;
    }
}
