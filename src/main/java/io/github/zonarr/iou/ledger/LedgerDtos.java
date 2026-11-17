package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.money.SplitType;
import io.github.zonarr.iou.user.UserSummary;
import jakarta.validation.Valid;
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

    /** One of several people who paid, and how much (in the expense's currency). */
    public record PayerInput(@NotNull Long userId, @NotNull BigDecimal amount) {
    }

    /**
     * One person the expense was for.
     *
     * @param value EXACT: their amount; PERCENT: their percentage; SHARES: their share count;
     *              EQUAL: leave out
     */
    public record ShareInput(@NotNull Long userId, BigDecimal value) {
    }

    /**
     * An expense: who paid, and who it was for.
     *
     * <p><b>Who paid</b> - give either {@code payerId} (one person paid everything) or
     * {@code payers} (several people, whose amounts must add up to {@code amount}).
     *
     * <p><b>Who it was for</b> - {@code splitType} defaults to EQUAL. For an equal split give
     * {@code participantIds}. For EXACT, PERCENT and SHARES give {@code shares} with a value
     * per person: amounts that add up to the total, percentages that add up to 100, or
     * relative share counts.
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
            @Valid List<PayerInput> payers,
            SplitType splitType,
            List<Long> participantIds,
            @Valid List<ShareInput> shares) {
    }

    /**
     * "from paid to amount". {@code groupId} is optional: leave it out for a payment outside groups.
     */
    public record PaymentRequest(
            Long groupId,
            @NotNull Long fromUserId,
            @NotNull Long toUserId,
            @NotNull BigDecimal amount,
            String currency,
            BigDecimal exchangeRate,
            @Size(max = 200) String note) {
    }

    public record PaymentView(
            Long id,
            Long groupId,
            UserSummary from,
            UserSummary to,
            BigDecimal originalAmount,
            String currency,
            BigDecimal exchangeRate,
            BigDecimal thbAmount,
            String note,
            ConfirmationStatus status,
            UserSummary createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** Everything waiting for the logged-in person's answer. */
    public record PendingView(List<DebtView> debts, List<PaymentView> payments) {
    }

    /** Optional filter for "accept all": only entries of this group. */
    public record AcceptAllRequest(Long groupId) {
    }

    public record AcceptAllResult(int debtsAccepted, int paymentsConfirmed) {
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
