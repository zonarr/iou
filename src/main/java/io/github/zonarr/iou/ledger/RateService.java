package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.money.Money;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers exchange rates so nobody has to retype them: the form is pre-filled with the
 * last rate used for a currency. Rates are always typed in by a person, never fetched.
 */
@Service
public class RateService {

    /** Every row that carries a rate: expenses, standalone debts and payments. */
    private static final String RATED_ROWS = """
            SELECT group_id, created_by, currency, exchange_rate, updated_at, deleted_at FROM expenses
            UNION ALL
            SELECT group_id, created_by, currency, exchange_rate, updated_at, deleted_at FROM debts
             WHERE expense_id IS NULL
            UNION ALL
            SELECT group_id, created_by, currency, exchange_rate, updated_at, deleted_at FROM payments
            """;

    private final JdbcClient jdbc;

    public RateService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Latest rate per currency used in the group, e.g. {@code {"EUR": 38.5, "CHF": 43.2}}. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> lastUsedInGroup(long groupId) {
        return query("group_id = :id", groupId);
    }

    /** Latest rate per currency this person entered anywhere (used outside groups). */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> lastUsedBy(long userId) {
        return query("created_by = :id", userId);
    }

    private Map<String, BigDecimal> query(String condition, long id) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        jdbc.sql("SELECT DISTINCT ON (currency) currency, exchange_rate FROM (" + RATED_ROWS + ") r"
                        + " WHERE " + condition + " AND deleted_at IS NULL AND currency <> :base"
                        + " ORDER BY currency, updated_at DESC")
                .param("id", id).param("base", Money.BASE_CURRENCY)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getBigDecimal(2)))
                .list()
                .forEach(e -> rates.put(e.getKey(), Money.displayRate(e.getValue())));
        return rates;
    }
}
