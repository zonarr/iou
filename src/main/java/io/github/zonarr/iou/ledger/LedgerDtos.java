package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.money.SplitType;
import io.github.zonarr.iou.user.UserSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class LedgerDtos {

    private LedgerDtos() {
    }

    /**
     * "debtor owes creditor amount".
     *
     * @param currency     ISO 4217 code; THB if left out
     * @param exchangeRate THB per 1 unit of {@code currency}; required unless the currency is THB
     */
    public record DebtRequest(
            @NotNull Long debtorId,
            @NotNull Long creditorId,
            @NotNull BigDecimal amount,
            String currency,
            BigDecimal exchangeRate,
            @NotBlank @Size(max = 200) String description) {
    }

    /**
     * An expense split equally: {@code payerId} paid {@code amount} for {@code participantIds}.
     *
     * @param spentOn the day it was paid; today if left out
     */
    public record ExpenseRequest(
            @NotBlank @Size(max = 200) String description,
            @NotNull BigDecimal amount,
            String currency,
            BigDecimal exchangeRate,
            LocalDate spentOn,
            Long payerId,
            List<Long> participantIds) {
    }

    public record DebtView(
            Long id,
            Long groupId,
            Long expenseId,
            UserSummary debtor,
            UserSummary creditor,
            BigDecimal originalAmount,
            String currency,
            BigDecimal exchangeRate,
            BigDecimal thbAmount,
            String description,
            ConfirmationStatus status,
            UserSummary createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PayerView(UserSummary user, BigDecimal originalAmount, BigDecimal thbAmount) {
    }

    /** @param weight the percentage or share count typed in; null for equal and exact splits */
    public record ShareView(UserSummary user, BigDecimal originalAmount, BigDecimal thbAmount, BigDecimal weight) {
    }

    public record ExpenseView(
            Long id,
            Long groupId,
            String description,
            BigDecimal originalAmount,
            String currency,
            BigDecimal exchangeRate,
            BigDecimal thbAmount,
            SplitType splitType,
            LocalDate spentOn,
            List<PayerView> payers,
            List<ShareView> shares,
            List<DebtView> debts,
            UserSummary createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }
}
