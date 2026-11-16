package io.github.zonarr.iou.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

/**
 * The money rules, in one place.
 *
 * <ul>
 *   <li>Amounts are {@link BigDecimal} with exactly 2 decimal places (satang, cents). Never double.</li>
 *   <li>Balances are kept in the base currency, THB.</li>
 *   <li>An amount in another currency is converted with a manually entered rate
 *       ("1 EUR = 38.50 THB") and rounded half-up to 2 places.</li>
 * </ul>
 */
public final class Money {

    public static final String BASE_CURRENCY = "THB";
    public static final int SCALE = 2;
    /** Rates keep up to 8 decimal places, enough for currencies worth a tiny fraction of a baht. */
    public static final int RATE_SCALE = 8;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99");
    public static final BigDecimal MAX_RATE = new BigDecimal("1000000");

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Money() {
    }

    /**
     * Checks an amount typed by a user and returns it with exactly 2 decimal places.
     *
     * @throws MoneyException if it is missing, not positive, too large or has more than 2 decimals
     */
    public static BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null) {
            throw new MoneyException("amount_required", "An amount is required");
        }
        if (amount.signum() <= 0) {
            throw new MoneyException("amount_not_positive", "The amount must be more than zero");
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new MoneyException("amount_too_precise", "Amounts can have at most 2 decimal places");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new MoneyException("amount_too_large", "The amount is too large");
        }
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** Normalises an ISO 4217 code ("eur" -> "EUR"); a missing code means THB. */
    public static String requireCurrency(String code) {
        if (code == null || code.isBlank()) {
            return BASE_CURRENCY;
        }
        String upper = code.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(upper);
        } catch (IllegalArgumentException e) {
            throw new MoneyException("unknown_currency", "Unknown currency: " + code);
        }
        return upper;
    }

    /**
     * Checks the exchange rate for a currency: THB is always 1; anything else needs a
     * positive rate meaning "1 unit of the currency = this many THB".
     */
    public static BigDecimal requireRate(String currency, BigDecimal rate) {
        if (BASE_CURRENCY.equals(currency)) {
            if (rate != null && rate.compareTo(BigDecimal.ONE) != 0) {
                throw new MoneyException("rate_must_be_one", "THB amounts do not take an exchange rate");
            }
            return BigDecimal.ONE.setScale(RATE_SCALE);
        }
        if (rate == null) {
            throw new MoneyException("rate_required", "An exchange rate to THB is required for " + currency);
        }
        if (rate.signum() <= 0) {
            throw new MoneyException("rate_not_positive", "The exchange rate must be more than zero");
        }
        if (rate.stripTrailingZeros().scale() > RATE_SCALE) {
            throw new MoneyException("rate_too_precise", "Exchange rates can have at most 8 decimal places");
        }
        if (rate.compareTo(MAX_RATE) > 0) {
            throw new MoneyException("rate_too_large", "The exchange rate is too large");
        }
        return rate.setScale(RATE_SCALE, RoundingMode.UNNECESSARY);
    }

    /** A rate without padding zeros, for display: 38.50000000 -> 38.5, 40.00000000 -> 40. */
    public static BigDecimal displayRate(BigDecimal rate) {
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** original x rate, rounded half-up to satang. The one and only conversion formula. */
    public static BigDecimal toThb(BigDecimal original, BigDecimal rate) {
        return original.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Validates amount, currency and rate together and converts to THB.
     *
     * @throws MoneyException if any part is invalid, or the amount is worth less than 1 satang
     */
    public static MoneyAmount convert(BigDecimal amount, String currencyCode, BigDecimal rate) {
        BigDecimal original = requireAmount(amount);
        String currency = requireCurrency(currencyCode);
        BigDecimal checkedRate = requireRate(currency, rate);
        BigDecimal thb = toThb(original, checkedRate);
        if (thb.signum() == 0) {
            throw new MoneyException("amount_too_small", "That amount is worth less than 0.01 THB");
        }
        if (thb.compareTo(MAX_AMOUNT) > 0) {
            throw new MoneyException("amount_too_large", "The amount is too large");
        }
        return new MoneyAmount(original, currency, checkedRate, thb);
    }
}
