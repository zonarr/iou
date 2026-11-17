package io.github.zonarr.iou.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @ParameterizedTest(name = "{0} at {1} = {2} THB")
    @CsvSource({
            // amount, rate, expected THB
            "100.00, 38.50,     3850.00",
            "33.33,  38.50,     1283.21",   // 1283.205  -> half rounds up
            "0.01,   38.50,     0.39",      // 0.385     -> half rounds up
            "19.99,  38.4321,   768.26",    // 768.257679
            "10.00,  43.21,     432.10",
            "1.00,   38.004,    38.00",     // 38.004    -> down
            "1.00,   38.005,    38.01",     // exactly half -> up
            "1.00,   38.0049999, 38.00",    // just under half -> down
            "250.00, 0.23456789, 58.64",    // 58.6419725, e.g. a low-value currency
            "999999.99, 1.00000001, 1000000.00"
    })
    void convertsWithHalfUpRoundingToSatang(String amount, String rate, String expected) {
        assertThat(Money.toThb(new BigDecimal(amount), new BigDecimal(rate))).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void conversionIsDeterministic() {
        BigDecimal first = Money.toThb(new BigDecimal("123.45"), new BigDecimal("38.123456"));
        for (int i = 0; i < 100; i++) {
            assertThat(Money.toThb(new BigDecimal("123.45"), new BigDecimal("38.123456"))).isEqualTo(first);
        }
        assertThat(first.scale()).isEqualTo(2);
    }

    @Test
    void thbConvertsOneToOneAndIgnoresMissingRate() {
        MoneyAmount money = Money.convert(new BigDecimal("900"), "THB", null);

        assertThat(money.originalAmount()).isEqualTo(new BigDecimal("900.00"));
        assertThat(money.thbAmount()).isEqualTo(new BigDecimal("900.00"));
        assertThat(money.exchangeRate()).isEqualByComparingTo("1");
        assertThat(money.currency()).isEqualTo("THB");
    }

    @Test
    void missingCurrencyMeansThb() {
        assertThat(Money.convert(new BigDecimal("5"), null, null).currency()).isEqualTo("THB");
        assertThat(Money.convert(new BigDecimal("5"), " ", null).currency()).isEqualTo("THB");
    }

    @Test
    void thbWithARateOtherThanOneIsRefused() {
        assertThat(Money.convert(new BigDecimal("5"), "THB", new BigDecimal("1.0")).thbAmount())
                .isEqualTo(new BigDecimal("5.00"));
        assertCode(() -> Money.convert(new BigDecimal("5"), "THB", new BigDecimal("38.5")), "rate_must_be_one");
    }

    @Test
    void foreignCurrencyStoresOriginalRateAndThb() {
        MoneyAmount money = Money.convert(new BigDecimal("12.5"), "eur", new BigDecimal("38.5"));

        assertThat(money.originalAmount()).isEqualTo(new BigDecimal("12.50"));
        assertThat(money.currency()).isEqualTo("EUR");
        assertThat(money.exchangeRate()).isEqualByComparingTo("38.5");
        assertThat(money.thbAmount()).isEqualTo(new BigDecimal("481.25"));
    }

    @Test
    void acceptsAnyIso4217Currency() {
        for (String code : new String[] {"THB", "EUR", "CHF", "USD", "GBP", "JPY", "HUF", "CZK"}) {
            assertThat(Money.requireCurrency(code.toLowerCase())).isEqualTo(code);
        }
        assertCode(() -> Money.requireCurrency("EURO"), "unknown_currency");
        assertCode(() -> Money.requireCurrency("XX1"), "unknown_currency");
    }

    @Test
    void foreignCurrencyNeedsAPositiveRate() {
        assertCode(() -> Money.convert(new BigDecimal("5"), "EUR", null), "rate_required");
        assertCode(() -> Money.convert(new BigDecimal("5"), "EUR", BigDecimal.ZERO), "rate_not_positive");
        assertCode(() -> Money.convert(new BigDecimal("5"), "EUR", new BigDecimal("-38")), "rate_not_positive");
        assertCode(() -> Money.convert(new BigDecimal("5"), "EUR", new BigDecimal("38.123456789")), "rate_too_precise");
        assertCode(() -> Money.convert(new BigDecimal("5"), "EUR", new BigDecimal("1000001")), "rate_too_large");
    }

    @Test
    void amountsMustBePositiveWithAtMostTwoDecimals() {
        assertCode(() -> Money.requireAmount(null), "amount_required");
        assertCode(() -> Money.requireAmount(BigDecimal.ZERO), "amount_not_positive");
        assertCode(() -> Money.requireAmount(new BigDecimal("-1")), "amount_not_positive");
        assertCode(() -> Money.requireAmount(new BigDecimal("1.005")), "amount_too_precise");
        assertCode(() -> Money.requireAmount(new BigDecimal("1000000000")), "amount_too_large");
        assertThat(Money.requireAmount(new BigDecimal("1.5"))).isEqualTo(new BigDecimal("1.50"));
        assertThat(Money.requireAmount(new BigDecimal("1.500"))).isEqualTo(new BigDecimal("1.50"));
        assertThat(Money.requireAmount(new BigDecimal("2E+2"))).isEqualTo(new BigDecimal("200.00"));
    }

    @Test
    void anAmountWorthLessThanOneSatangIsRefused() {
        assertCode(() -> Money.convert(new BigDecimal("0.01"), "VND", new BigDecimal("0.0014")), "amount_too_small");
        // 0.005 THB rounds up to 0.01, so this one is fine.
        assertThat(Money.convert(new BigDecimal("0.01"), "JPY", new BigDecimal("0.5")).thbAmount())
                .isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void sameValueIgnoresScale() {
        MoneyAmount a = new MoneyAmount(new BigDecimal("10.00"), "EUR", new BigDecimal("38.50000000"),
                new BigDecimal("385.00"));
        MoneyAmount b = new MoneyAmount(new BigDecimal("10"), "EUR", new BigDecimal("38.5"), new BigDecimal("385"));

        assertThat(a.sameValueAs(b)).isTrue();
        assertThat(a.sameValueAs(b.withAmounts(new BigDecimal("10.01"), new BigDecimal("385.39")))).isFalse();
        assertThat(a.sameValueAs(null)).isFalse();
    }

    static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(MoneyException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
