package io.github.zonarr.iou.money;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/**
 * An amount as it was entered, plus what it is worth in THB.
 *
 * @param originalAmount the amount in {@code currency}, 2 decimal places
 * @param currency       ISO 4217 code
 * @param exchangeRate   THB per 1 unit of {@code currency} (1 for THB)
 * @param thbAmount      the stored THB value, 2 decimal places; balances use only this
 */
@Embeddable
public record MoneyAmount(
        @Column(name = "original_amount", nullable = false) BigDecimal originalAmount,
        @Column(name = "currency", nullable = false) String currency,
        @Column(name = "exchange_rate", nullable = false) BigDecimal exchangeRate,
        @Column(name = "thb_amount", nullable = false) BigDecimal thbAmount) {

    /** Same currency and rate, different amounts (used for the parts of a split expense). */
    public MoneyAmount withAmounts(BigDecimal original, BigDecimal thb) {
        return new MoneyAmount(original, currency, exchangeRate, thb);
    }

    /** True if nothing about the money differs (scale-insensitive). */
    public boolean sameValueAs(MoneyAmount other) {
        return other != null
                && originalAmount.compareTo(other.originalAmount) == 0
                && currency.equals(other.currency)
                && exchangeRate.compareTo(other.exchangeRate) == 0
                && thbAmount.compareTo(other.thbAmount) == 0;
    }
}
