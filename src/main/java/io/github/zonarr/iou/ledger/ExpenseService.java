package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.audit.AuditAction;
import io.github.zonarr.iou.audit.AuditEntity;
import io.github.zonarr.iou.audit.AuditService;
import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.ledger.LedgerDtos.ExpenseRequest;
import io.github.zonarr.iou.ledger.LedgerDtos.ExpenseView;
import io.github.zonarr.iou.money.Money;
import io.github.zonarr.iou.money.MoneyAmount;
import io.github.zonarr.iou.money.SplitCalculator;
import io.github.zonarr.iou.money.SplitCalculator.Breakdown;
import io.github.zonarr.iou.money.SplitCalculator.Participant;
import io.github.zonarr.iou.money.SplitCalculator.Payer;
import io.github.zonarr.iou.money.SplitType;
import io.github.zonarr.iou.user.User;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {

    private final ExpenseRepository expenses;
    private final ExpensePayerRepository payers;
    private final ExpenseShareRepository shares;
    private final DebtRepository debts;
    private final LedgerAccess access;
    private final LedgerMapper mapper;
    private final AuditService audit;

    public ExpenseService(ExpenseRepository expenses, ExpensePayerRepository payers, ExpenseShareRepository shares,
            DebtRepository debts, LedgerAccess access, LedgerMapper mapper, AuditService audit) {
        this.audit = audit;
        this.expenses = expenses;
        this.payers = payers;
        this.shares = shares;
        this.debts = debts;
        this.access = access;
        this.mapper = mapper;
    }

    /** What the request asks for, validated and worked out, before anything is stored. */
    private record Plan(MoneyAmount total, Breakdown breakdown, Map<Long, User> people) {
    }

    /** Records an expense and creates one debt for everybody who owes a payer. */
    @Transactional
    public ExpenseView create(long actorId, Long groupId, ExpenseRequest request) {
        Group group = access.groupFor(groupId, actorId);
        Plan plan = plan(actorId, group, request);

        Expense expense = expenses.save(new Expense(groupId, request.description().trim(), plan.total(),
                plan.breakdown().splitType(), spentOn(request), actorId));
        storeLines(expense, plan.breakdown());
        for (SplitCalculator.DebtLine line : plan.breakdown().debts()) {
            ConfirmationStatus status = ConfirmationPolicy.statusFor(actorId, plan.people().get(line.debtorId()), group);
            debts.save(new Debt(groupId, expense.getId(), line.debtorId(), line.creditorId(),
                    plan.total().withAmounts(line.original(), line.thb()), expense.getDescription(), status, actorId));
        }
        audit.record(AuditEntity.EXPENSE, expense.getId(), AuditAction.CREATED, groupId, actorId,
                plan.people().keySet(), null, snapshot(expense));
        return mapper.expense(expense);
    }

    /**
     * Replaces an expense with new details and re-works its debts. A debt whose people and
     * amounts come out unchanged keeps its status; a debt whose amount changed, or that is
     * new, goes through confirmation again as if the editor had just recorded it; a debt
     * that is no longer needed is removed.
     */
    @Transactional
    public ExpenseView update(long actorId, long expenseId, ExpenseRequest request) {
        Expense expense = requireVisible(actorId, expenseId);
        Group group = access.groupFor(expense.getGroupId(), actorId);
        access.requireCanEdit(actorId, group, expense.getCreatedBy());
        Map<String, Object> before = snapshot(expense);
        Set<Long> involved = new HashSet<>(involved(expense));

        Plan plan = plan(actorId, group, request);
        expense.change(request.description().trim(), plan.total(), plan.breakdown().splitType(),
                request.spentOn() != null ? request.spentOn() : expense.getSpentOn());
        payers.deleteByExpenseId(expenseId);
        shares.deleteByExpenseId(expenseId);
        storeLines(expense, plan.breakdown());

        Map<String, Debt> existing = new HashMap<>();
        debts.findActiveByExpenseIds(List.of(expenseId))
                .forEach(d -> existing.put(d.getDebtorId() + ">" + d.getCreditorId(), d));
        for (SplitCalculator.DebtLine line : plan.breakdown().debts()) {
            MoneyAmount money = plan.total().withAmounts(line.original(), line.thb());
            ConfirmationStatus fresh = ConfirmationPolicy.statusFor(actorId, plan.people().get(line.debtorId()), group);
            Debt debt = existing.remove(line.debtorId() + ">" + line.creditorId());
            if (debt == null) {
                debts.save(new Debt(expense.getGroupId(), expenseId, line.debtorId(), line.creditorId(), money,
                        expense.getDescription(), fresh, actorId));
            } else if (debt.getMoney().sameValueAs(money)) {
                if (!debt.getDescription().equals(expense.getDescription())) {
                    debt.setDescription(expense.getDescription());
                }
            } else {
                debt.change(line.debtorId(), line.creditorId(), money, expense.getDescription());
                debt.setStatus(fresh);
            }
        }
        existing.values().forEach(Debt::markDeleted);
        debts.flush();

        involved.addAll(plan.people().keySet());
        audit.record(AuditEntity.EXPENSE, expenseId, AuditAction.UPDATED, expense.getGroupId(), actorId,
                involved, before, snapshot(expense));
        return mapper.expense(expense);
    }

    /** Deletes the expense together with the debts it produced. */
    @Transactional
    public void delete(long actorId, long expenseId) {
        Expense expense = requireVisible(actorId, expenseId);
        access.requireCanEdit(actorId, access.groupFor(expense.getGroupId(), actorId), expense.getCreatedBy());
        Map<String, Object> before = snapshot(expense);
        Set<Long> involved = involved(expense);
        debts.findActiveByExpenseIds(List.of(expenseId)).forEach(Debt::markDeleted);
        expense.markDeleted();
        audit.record(AuditEntity.EXPENSE, expenseId, AuditAction.DELETED, expense.getGroupId(), actorId,
                involved, before, null);
    }

    private Map<String, Object> snapshot(Expense expense) {
        debts.flush();
        List<Long> id = List.of(expense.getId());
        return Snapshots.of(expense, payers.findByExpenseIds(id), shares.findByExpenseIds(id),
                debts.findActiveByExpenseIds(id));
    }

    private Set<Long> involved(Expense expense) {
        Set<Long> ids = new HashSet<>();
        ids.add(expense.getCreatedBy());
        List<Long> id = List.of(expense.getId());
        payers.findByExpenseIds(id).forEach(p -> ids.add(p.getUserId()));
        shares.findByExpenseIds(id).forEach(s -> ids.add(s.getUserId()));
        return ids;
    }

    @Transactional(readOnly = true)
    public List<ExpenseView> listInGroup(long actorId, long groupId) {
        access.groupFor(groupId, actorId);
        return mapper.expenses(expenses.findActiveInGroup(groupId));
    }

    @Transactional(readOnly = true)
    public List<ExpenseView> listDirect(long actorId) {
        return mapper.expenses(expenses.findActiveDirectFor(actorId));
    }

    @Transactional(readOnly = true)
    public ExpenseView get(long actorId, long expenseId) {
        return mapper.expense(requireVisible(actorId, expenseId));
    }

    Expense requireVisible(long actorId, long expenseId) {
        Expense expense = expenses.findById(expenseId).filter(e -> !e.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Expense"));
        access.requireCanSee(actorId, expense.getGroupId(),
                expense.getGroupId() == null ? involved(expense) : Set.of());
        return expense;
    }

    private Plan plan(long actorId, Group group, ExpenseRequest request) {
        MoneyAmount total = Money.convert(request.amount(), request.currency(), request.exchangeRate());
        if (request.payerId() == null) {
            throw ApiException.badRequest("payers_required", "Say who paid");
        }
        List<Payer> payerList = List.of(new Payer(request.payerId(), total.originalAmount()));
        List<Participant> participantList = new ArrayList<>();
        if (request.participantIds() != null) {
            request.participantIds().forEach(id -> participantList.add(new Participant(requireId(id), null)));
        }
        Breakdown breakdown = SplitCalculator.calculate(total, SplitType.EQUAL, payerList, participantList);

        Set<Long> everyone = new HashSet<>();
        breakdown.payers().forEach(p -> everyone.add(p.userId()));
        breakdown.shares().forEach(s -> everyone.add(s.userId()));
        return new Plan(total, breakdown, access.requireParties(actorId, group, everyone));
    }

    private void storeLines(Expense expense, Breakdown breakdown) {
        breakdown.payers().forEach(p ->
                payers.save(new ExpensePayer(expense.getId(), p.userId(), p.original(), p.thb())));
        breakdown.shares().forEach(s ->
                shares.save(new ExpenseShare(expense.getId(), s.userId(), s.original(), s.thb(), s.weight())));
    }

    private static long requireId(Long id) {
        if (id == null) {
            throw ApiException.badRequest("person_required", "A person is missing");
        }
        return id;
    }

    private static LocalDate spentOn(ExpenseRequest request) {
        return request.spentOn() != null ? request.spentOn() : LocalDate.now(ZoneOffset.UTC);
    }
}
