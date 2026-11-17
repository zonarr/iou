package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.ledger.LedgerDtos.DebtView;
import io.github.zonarr.iou.ledger.LedgerDtos.ExpenseView;
import io.github.zonarr.iou.ledger.LedgerDtos.PayerView;
import io.github.zonarr.iou.ledger.LedgerDtos.PaymentView;
import io.github.zonarr.iou.ledger.LedgerDtos.ShareView;
import io.github.zonarr.iou.money.Money;
import io.github.zonarr.iou.user.UserDirectory;
import io.github.zonarr.iou.user.UserSummary;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Turns ledger entities into the JSON views, resolving ids to names in one query per list. */
@Component
public class LedgerMapper {

    private final UserDirectory directory;
    private final DebtRepository debts;
    private final ExpensePayerRepository payers;
    private final ExpenseShareRepository shares;

    public LedgerMapper(UserDirectory directory, DebtRepository debts, ExpensePayerRepository payers,
            ExpenseShareRepository shares) {
        this.directory = directory;
        this.debts = debts;
        this.payers = payers;
        this.shares = shares;
    }

    public DebtView debt(Debt debt) {
        return debts(List.of(debt)).get(0);
    }

    public List<DebtView> debts(List<Debt> list) {
        Set<Long> ids = new HashSet<>();
        list.forEach(d -> collectIds(d, ids));
        Map<Long, UserSummary> people = directory.summaries(ids);
        return list.stream().map(d -> toView(d, people)).toList();
    }

    public ExpenseView expense(Expense expense) {
        return expenses(List.of(expense)).get(0);
    }

    public List<ExpenseView> expenses(List<Expense> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> expenseIds = list.stream().map(Expense::getId).toList();
        Map<Long, List<ExpensePayer>> payersByExpense = payers.findByExpenseIds(expenseIds).stream()
                .collect(Collectors.groupingBy(ExpensePayer::getExpenseId));
        Map<Long, List<ExpenseShare>> sharesByExpense = shares.findByExpenseIds(expenseIds).stream()
                .collect(Collectors.groupingBy(ExpenseShare::getExpenseId));
        Map<Long, List<Debt>> debtsByExpense = debts.findActiveByExpenseIds(expenseIds).stream()
                .collect(Collectors.groupingBy(Debt::getExpenseId));

        Set<Long> ids = new HashSet<>();
        list.forEach(e -> ids.add(e.getCreatedBy()));
        payersByExpense.values().forEach(l -> l.forEach(p -> ids.add(p.getUserId())));
        sharesByExpense.values().forEach(l -> l.forEach(s -> ids.add(s.getUserId())));
        debtsByExpense.values().forEach(l -> l.forEach(d -> collectIds(d, ids)));
        Map<Long, UserSummary> people = directory.summaries(ids);

        return list.stream().map(e -> new ExpenseView(
                e.getId(), e.getGroupId(), e.getDescription(),
                e.getMoney().originalAmount(), e.getMoney().currency(),
                Money.displayRate(e.getMoney().exchangeRate()), e.getMoney().thbAmount(),
                e.getSplitType(), e.getSpentOn(),
                payersByExpense.getOrDefault(e.getId(), List.of()).stream()
                        .map(p -> new PayerView(people.get(p.getUserId()), p.getOriginalAmount(), p.getThbAmount()))
                        .toList(),
                sharesByExpense.getOrDefault(e.getId(), List.of()).stream()
                        .map(s -> new ShareView(people.get(s.getUserId()), s.getOriginalAmount(), s.getThbAmount(),
                                s.getWeight() == null ? null : Money.displayRate(s.getWeight())))
                        .toList(),
                debtsByExpense.getOrDefault(e.getId(), List.of()).stream().map(d -> toView(d, people)).toList(),
                people.get(e.getCreatedBy()), e.getCreatedAt(), e.getUpdatedAt())).toList();
    }

    public PaymentView payment(Payment payment) {
        return payments(List.of(payment)).get(0);
    }

    public List<PaymentView> payments(List<Payment> list) {
        Set<Long> ids = new HashSet<>();
        list.forEach(p -> {
            ids.add(p.getFromUserId());
            ids.add(p.getToUserId());
            ids.add(p.getCreatedBy());
        });
        Map<Long, UserSummary> people = directory.summaries(ids);
        return list.stream().map(p -> new PaymentView(p.getId(), p.getGroupId(),
                people.get(p.getFromUserId()), people.get(p.getToUserId()),
                p.getMoney().originalAmount(), p.getMoney().currency(),
                Money.displayRate(p.getMoney().exchangeRate()), p.getMoney().thbAmount(),
                p.getNote(), p.getStatus(), people.get(p.getCreatedBy()),
                p.getCreatedAt(), p.getUpdatedAt())).toList();
    }

    private static void collectIds(Debt d, Set<Long> ids) {
        ids.add(d.getDebtorId());
        ids.add(d.getCreditorId());
        ids.add(d.getCreatedBy());
    }

    private static DebtView toView(Debt d, Map<Long, UserSummary> people) {
        return new DebtView(d.getId(), d.getGroupId(), d.getExpenseId(),
                people.get(d.getDebtorId()), people.get(d.getCreditorId()),
                d.getMoney().originalAmount(), d.getMoney().currency(),
                Money.displayRate(d.getMoney().exchangeRate()), d.getMoney().thbAmount(),
                d.getDescription(), d.getStatus(), people.get(d.getCreatedBy()),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
