package io.github.zonarr.iou.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AllocatorTest {

    @Test
    void readmeExample_900BetweenThree() {
        assertThat(Allocator.splitEqually(bd("900.00"), 3)).isEqualTo(amounts("300.00", "300.00", "300.00"));
    }

    @Test
    void leftoverSatangGoToTheFirstPeople() {
        assertThat(Allocator.splitEqually(bd("100.00"), 3)).isEqualTo(amounts("33.34", "33.33", "33.33"));
        assertThat(Allocator.splitEqually(bd("100.01"), 3)).isEqualTo(amounts("33.34", "33.34", "33.33"));
        assertThat(Allocator.splitEqually(bd("0.05"), 3)).isEqualTo(amounts("0.02", "0.02", "0.01"));
        assertThat(Allocator.splitEqually(bd("10.00"), 7))
                .isEqualTo(amounts("1.43", "1.43", "1.43", "1.43", "1.43", "1.43", "1.42"));
    }

    @Test
    void fewerSatangThanPeople() {
        assertThat(Allocator.splitEqually(bd("0.01"), 3)).isEqualTo(amounts("0.01", "0.00", "0.00"));
        assertThat(Allocator.splitEqually(bd("0.02"), 5)).isEqualTo(amounts("0.01", "0.01", "0.00", "0.00", "0.00"));
    }

    @Test
    void onePersonGetsEverything() {
        assertThat(Allocator.splitEqually(bd("123.45"), 1)).isEqualTo(amounts("123.45"));
    }

    @Test
    void zeroSplitsIntoZeros() {
        assertThat(Allocator.splitEqually(bd("0.00"), 3)).isEqualTo(amounts("0.00", "0.00", "0.00"));
    }

    @Test
    void weightedSplit() {
        // 2 : 1 : 1 shares of 100.00
        assertThat(Allocator.allocate(bd("100.00"), weights("2", "1", "1")))
                .isEqualTo(amounts("50.00", "25.00", "25.00"));
        // percentages 50 / 30 / 20 of 99.99: exact parts 49.995, 29.997, 19.998.
        // Rounded down: 49.99 + 29.99 + 19.99 = 99.97; the 2 leftover satang go to the
        // biggest lost fractions (0.8 for the third, 0.7 for the second).
        assertThat(Allocator.allocate(bd("99.99"), weights("50", "30", "20")))
                .isEqualTo(amounts("49.99", "30.00", "20.00"));
    }

    @Test
    void largestRemainderWinsBeforePosition() {
        // 0.10 by 1 : 2 -> exact 0.0333.. and 0.0666..; the second lost more, so it gets the satang.
        assertThat(Allocator.allocate(bd("0.10"), weights("1", "2"))).isEqualTo(amounts("0.03", "0.07"));
    }

    @Test
    void fractionalWeightsAndZeroWeights() {
        assertThat(Allocator.allocate(bd("10.00"), weights("0.5", "1.5"))).isEqualTo(amounts("2.50", "7.50"));
        assertThat(Allocator.allocate(bd("10.00"), weights("33.3333", "66.6667")))
                .isEqualTo(amounts("3.33", "6.67"));
        assertThat(Allocator.allocate(bd("10.00"), weights("0", "1", "0"))).isEqualTo(amounts("0.00", "10.00", "0.00"));
    }

    @Test
    void manyPeople_alwaysSumsToTheTotalAndNobodyDiffersByMoreThanOneSatang() {
        for (int people = 1; people <= 60; people++) {
            for (String total : new String[] {"0.01", "0.99", "1.00", "100.00", "999.99", "123456.78", "999999999.99"}) {
                List<BigDecimal> parts = Allocator.splitEqually(bd(total), people);

                assertThat(parts).hasSize(people);
                assertThat(sum(parts)).as("%s between %d", total, people).isEqualTo(bd(total));
                BigDecimal spread = parts.get(0).subtract(parts.get(people - 1));
                assertThat(spread).isBetween(bd("0.00"), bd("0.01"));
                assertThat(parts).isSortedAccordingTo((a, b) -> b.compareTo(a)); // bigger parts first
            }
        }
    }

    @Test
    void randomWeightedSplitsAlwaysSumExactlyAndStayWithinOneSatangOfTheExactShare() {
        Random random = new Random(20261216);
        for (int round = 0; round < 2000; round++) {
            int n = 1 + random.nextInt(12);
            BigDecimal total = BigDecimal.valueOf(random.nextInt(5_000_000), 2);
            List<BigDecimal> weights = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                weights.add(BigDecimal.valueOf(1 + random.nextInt(10_000), random.nextInt(3)));
            }
            List<BigDecimal> parts = Allocator.allocate(total, weights);

            assertThat(sum(parts)).isEqualTo(total);
            BigDecimal weightSum = sum(weights);
            for (int i = 0; i < n; i++) {
                BigDecimal exact = total.multiply(weights.get(i)).divide(weightSum, 10, java.math.RoundingMode.HALF_UP);
                assertThat(parts.get(i).subtract(exact).abs()).isLessThan(bd("0.01"));
                assertThat(parts.get(i).scale()).isEqualTo(2);
            }
            assertThat(Allocator.allocate(total, weights)).isEqualTo(parts); // deterministic
        }
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> Allocator.splitEqually(bd("10.00"), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Allocator.allocate(bd("-1.00"), weights("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Allocator.allocate(bd("1.001"), weights("1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Allocator.allocate(bd("1.00"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Allocator.allocate(bd("1.00"), weights("0", "0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Allocator.allocate(bd("1.00"), weights("1", "-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    static List<BigDecimal> amounts(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    static List<BigDecimal> weights(String... values) {
        return amounts(values);
    }

    static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
