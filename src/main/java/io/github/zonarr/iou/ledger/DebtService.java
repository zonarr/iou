package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.audit.AuditAction;
import io.github.zonarr.iou.audit.AuditEntity;
import io.github.zonarr.iou.audit.AuditService;
import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.ledger.LedgerDtos.DebtRequest;
import io.github.zonarr.iou.ledger.LedgerDtos.DebtView;
import io.github.zonarr.iou.money.Money;
import io.github.zonarr.iou.money.MoneyAmount;
import io.github.zonarr.iou.user.User;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DebtService {

    private final DebtRepository debts;
    private final LedgerAccess access;
    private final LedgerMapper mapper;
    private final AuditService audit;

    public DebtService(DebtRepository debts, LedgerAccess access, LedgerMapper mapper, AuditService audit) {
        this.debts = debts;
        this.access = access;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** Records a simple IOU, in a group ({@code groupId} given) or one-on-one. */
    @Transactional
    public DebtView create(long actorId, Long groupId, DebtRequest request) {
        Group group = access.groupFor(groupId, actorId);
        MoneyAmount money = Money.convert(request.amount(), request.currency(), request.exchangeRate());
        Map<Long, User> people = requireTwoPeople(actorId, group, request);
        ConfirmationStatus status = ConfirmationPolicy.statusFor(actorId, people.get(request.debtorId()), group);
        Debt debt = debts.save(new Debt(groupId, null, request.debtorId(), request.creditorId(), money,
                request.description().trim(), status, actorId));
        audit.record(AuditEntity.DEBT, debt.getId(), AuditAction.CREATED, groupId, actorId, involved(debt),
                null, Snapshots.of(debt));
        return mapper.debt(debt);
    }

    /**
     * Changes a standalone IOU. If the people or the money change, the debt goes back through
     * confirmation exactly as if it had just been recorded by the editor; a description-only
     * edit leaves its status alone.
     */
    @Transactional
    public DebtView update(long actorId, long debtId, DebtRequest request) {
        Debt debt = requireEditable(actorId, debtId);
        Group group = access.groupFor(debt.getGroupId(), actorId);
        MoneyAmount money = Money.convert(request.amount(), request.currency(), request.exchangeRate());
        Map<Long, User> people = requireTwoPeople(actorId, group, request);

        Map<String, Object> before = Snapshots.of(debt);
        Set<Long> involved = involved(debt);
        boolean substantive = !debt.getDebtorId().equals(request.debtorId())
                || !debt.getCreditorId().equals(request.creditorId())
                || !debt.getMoney().sameValueAs(money);
        debt.change(request.debtorId(), request.creditorId(), money, request.description().trim());
        if (substantive) {
            debt.setStatus(ConfirmationPolicy.statusFor(actorId, people.get(request.debtorId()), group));
        }
        involved.addAll(involved(debt));
        audit.record(AuditEntity.DEBT, debt.getId(), AuditAction.UPDATED, debt.getGroupId(), actorId, involved,
                before, Snapshots.of(debt));
        return mapper.debt(debt);
    }

    @Transactional
    public void delete(long actorId, long debtId) {
        Debt debt = requireEditable(actorId, debtId);
        Map<String, Object> before = Snapshots.of(debt);
        debt.markDeleted();
        audit.record(AuditEntity.DEBT, debt.getId(), AuditAction.DELETED, debt.getGroupId(), actorId,
                involved(debt), before, null);
    }

    /** The debtor agrees: the debt now counts in balances. */
    @Transactional
    public DebtView accept(long actorId, long debtId) {
        return decide(actorId, debtId, ConfirmationStatus.CONFIRMED, AuditAction.ACCEPTED);
    }

    /** The debtor refuses: the debt stays visible but never counts. */
    @Transactional
    public DebtView reject(long actorId, long debtId) {
        return decide(actorId, debtId, ConfirmationStatus.REJECTED, AuditAction.REJECTED);
    }

    private DebtView decide(long actorId, long debtId, ConfirmationStatus outcome, AuditAction action) {
        Debt debt = requireVisible(actorId, debtId);
        if (!debt.getDebtorId().equals(actorId)) {
            throw ApiException.forbidden("debtor_only", "Only the person who owes this can accept or reject it");
        }
        if (debt.getStatus() != ConfirmationStatus.PENDING) {
            throw ApiException.conflict("not_pending", "This has already been answered");
        }
        Map<String, Object> before = Snapshots.of(debt);
        debt.setStatus(outcome);
        audit.record(AuditEntity.DEBT, debt.getId(), action, debt.getGroupId(), actorId, involved(debt),
                before, Snapshots.of(debt));
        return mapper.debt(debt);
    }

    @Transactional(readOnly = true)
    public List<DebtView> listInGroup(long actorId, long groupId) {
        access.groupFor(groupId, actorId);
        return mapper.debts(debts.findActiveInGroup(groupId));
    }

    @Transactional(readOnly = true)
    public List<DebtView> listDirect(long actorId) {
        return mapper.debts(debts.findActiveDirectFor(actorId));
    }

    @Transactional(readOnly = true)
    public DebtView get(long actorId, long debtId) {
        return mapper.debt(requireVisible(actorId, debtId));
    }

    Debt requireVisible(long actorId, long debtId) {
        Debt debt = debts.findById(debtId).filter(d -> !d.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Debt"));
        access.requireCanSee(actorId, debt.getGroupId(), involved(debt));
        return debt;
    }

    private Debt requireEditable(long actorId, long debtId) {
        Debt debt = requireVisible(actorId, debtId);
        access.requireCanEdit(actorId, access.groupFor(debt.getGroupId(), actorId), debt.getCreatedBy());
        if (debt.getExpenseId() != null) {
            throw ApiException.conflict("part_of_expense",
                    "This debt comes from an expense: change or delete the expense instead");
        }
        return debt;
    }

    private Map<Long, User> requireTwoPeople(long actorId, Group group, DebtRequest request) {
        if (request.debtorId().equals(request.creditorId())) {
            throw ApiException.badRequest("same_person", "Nobody can owe themselves");
        }
        return access.requireParties(actorId, group, List.of(request.debtorId(), request.creditorId()));
    }

    static Set<Long> involved(Debt debt) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(debt.getDebtorId());
        ids.add(debt.getCreditorId());
        ids.add(debt.getCreatedBy());
        return ids;
    }
}
