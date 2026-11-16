package io.github.zonarr.iou.ledger;

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

    public ExpenseService(ExpenseRepository expenses, ExpensePayerRepository payers, ExpenseShareRepository shares,
            DebtRepository debts, LedgerAccess access, LedgerMapper mapper) {
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
        return mapper.expense(expense);
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
        Set<Long> involved = new HashSet<>();
        involved.add(expense.getCreatedBy());
        if (expense.getGroupId() == null) {
            payers.findByExpenseIds(List.of(expenseId)).forEach(p -> involved.add(p.getUserId()));
            shares.findByExpenseIds(List.of(expenseId)).forEach(s -> involved.add(s.getUserId()));
        }
        access.requireCanSee(actorId, expense.getGroupId(), involved);
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
