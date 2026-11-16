package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.ledger.LedgerDtos.DebtRequest;
import io.github.zonarr.iou.ledger.LedgerDtos.DebtView;
import io.github.zonarr.iou.money.Money;
import io.github.zonarr.iou.money.MoneyAmount;
import io.github.zonarr.iou.user.User;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DebtService {

    private final DebtRepository debts;
    private final LedgerAccess access;
    private final LedgerMapper mapper;

    public DebtService(DebtRepository debts, LedgerAccess access, LedgerMapper mapper) {
        this.debts = debts;
        this.access = access;
        this.mapper = mapper;
    }

    /** Records a simple IOU, in a group ({@code groupId} given) or one-on-one. */
    @Transactional
    public DebtView create(long actorId, Long groupId, DebtRequest request) {
        Group group = access.groupFor(groupId, actorId);
        if (request.debtorId().equals(request.creditorId())) {
            throw ApiException.badRequest("same_person", "Nobody can owe themselves");
        }
        MoneyAmount money = Money.convert(request.amount(), request.currency(), request.exchangeRate());
        Map<Long, User> people = access.requireParties(actorId, group,
                List.of(request.debtorId(), request.creditorId()));
        ConfirmationStatus status = ConfirmationPolicy.statusFor(actorId, people.get(request.debtorId()), group);
        Debt debt = debts.save(new Debt(groupId, null, request.debtorId(), request.creditorId(), money,
                request.description().trim(), status, actorId));
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
        access.requireCanSee(actorId, debt.getGroupId(),
                List.of(debt.getDebtorId(), debt.getCreditorId(), debt.getCreatedBy()));
        return debt;
    }
}
