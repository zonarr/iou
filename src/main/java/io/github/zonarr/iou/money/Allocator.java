package io.github.zonarr.iou.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Splits an amount into parts that always add up to exactly the amount.
 *
 * <p>Uses the largest-remainder method: every part first gets its share rounded <em>down</em>
 * to the satang; the few satang left over go, one each, to the parts that lost the biggest
 * fraction. Ties go to whoever comes first in the list. The result depends only on the
 * inputs and their order, so it is fully deterministic.
 *
 * <p>Example: 100.00 split by weights 1:1:1 gives 33.34, 33.33, 33.33.
 */
public final class Allocator {

    private Allocator() {
    }

    /**
     * @param total   the amount to split, 2 decimal places, zero or more
     * @param weights relative size of each part (equal weights = equal split); none negative,
     *                at least one positive
     * @return one amount per weight, each with 2 decimal places, summing to {@code total}
     */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        if (total == null || total.signum() < 0 || total.stripTrailingZeros().scale() > Money.SCALE) {
            throw new IllegalArgumentException("total must be a non-negative amount with at most 2 decimals");
        }
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("at least one weight is required");
        }
        // Bring all weights to one scale so they become comparable whole numbers.
        int scale = weights.stream().mapToInt(w -> Math.max(w.scale(), 0)).max().orElse(0);
        List<BigInteger> w = new ArrayList<>(weights.size());
        BigInteger weightSum = BigInteger.ZERO;
        for (BigDecimal weight : weights) {
            if (weight == null || weight.signum() < 0) {
                throw new IllegalArgumentException("weights must not be negative");
            }
            BigInteger scaled = weight.setScale(scale).unscaledValue();
            w.add(scaled);
            weightSum = weightSum.add(scaled);
        }
        if (weightSum.signum() == 0) {
            throw new IllegalArgumentException("at least one weight must be positive");
        }

        BigInteger totalSatang = total.setScale(Money.SCALE).unscaledValue();
        int n = w.size();
        BigInteger[] parts = new BigInteger[n];
        BigInteger[] remainders = new BigInteger[n];
        BigInteger handedOut = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] qr = totalSatang.multiply(w.get(i)).divideAndRemainder(weightSum);
            parts[i] = qr[0];
            remainders[i] = qr[1];
            handedOut = handedOut.add(qr[0]);
        }

        // Fewer than n satang are left. Largest lost fraction first; earlier position wins ties.
        int leftover = totalSatang.subtract(handedOut).intValueExact();
        List<Integer> order = IntStream.range(0, n).boxed()
                .sorted(Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        for (int k = 0; k < leftover; k++) {
            int i = order.get(k);
            parts[i] = parts[i].add(BigInteger.ONE);
        }

        List<BigDecimal> result = new ArrayList<>(n);
        for (BigInteger part : parts) {
            result.add(new BigDecimal(part, Money.SCALE));
        }
        return result;
    }

    /** Equal split into {@code count} parts; the first parts get any leftover satang. */
    public static List<BigDecimal> splitEqually(BigDecimal total, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        return allocate(total, java.util.Collections.nCopies(count, BigDecimal.ONE));
    }
}
