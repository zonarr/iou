package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.audit.AuditAction;
import io.github.zonarr.iou.audit.AuditEntity;
import io.github.zonarr.iou.audit.AuditService;
import io.github.zonarr.iou.common.ApiException;
import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.ledger.LedgerDtos.PaymentRequest;
import io.github.zonarr.iou.ledger.LedgerDtos.PaymentView;
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
public class PaymentService {

    private final PaymentRepository payments;
    private final LedgerAccess access;
    private final LedgerMapper mapper;
    private final AuditService audit;

    public PaymentService(PaymentRepository payments, LedgerAccess access, LedgerMapper mapper,
            AuditService audit) {
        this.payments = payments;
        this.access = access;
        this.mapper = mapper;
        this.audit = audit;
    }

    /**
     * Records that money changed hands. It counts once the receiver confirms it, or at once
     * if the receiver recorded it, is a placeholder, or the group is trusted.
     */
    @Transactional
    public PaymentView create(long actorId, PaymentRequest request) {
        Group group = access.groupFor(request.groupId(), actorId);
        MoneyAmount money = Money.convert(request.amount(), request.currency(), request.exchangeRate());
        Map<Long, User> people = requireTwoPeople(actorId, group, request);
        ConfirmationStatus status = ConfirmationPolicy.statusFor(actorId, people.get(request.toUserId()), group);
        Payment payment = payments.save(new Payment(request.groupId(), request.fromUserId(), request.toUserId(),
                money, note(request), status, actorId));
        audit.record(AuditEntity.PAYMENT, payment.getId(), AuditAction.CREATED, payment.getGroupId(), actorId,
                involved(payment), null, Snapshots.of(payment));
        return mapper.payment(payment);
    }

    /** Like editing a debt: changing people or money restarts confirmation; a note-only edit does not. */
    @Transactional
    public PaymentView update(long actorId, long paymentId, PaymentRequest request) {
        Payment payment = requireEditable(actorId, paymentId);
        Group group = access.groupFor(payment.getGroupId(), actorId);
        MoneyAmount money = Money.convert(request.amount(), request.currency(), request.exchangeRate());
        Map<Long, User> people = requireTwoPeople(actorId, group, request);

        Map<String, Object> before = Snapshots.of(payment);
        Set<Long> involved = involved(payment);
        boolean substantive = !payment.getFromUserId().equals(request.fromUserId())
                || !payment.getToUserId().equals(request.toUserId())
                || !payment.getMoney().sameValueAs(money);
        payment.change(request.fromUserId(), request.toUserId(), money, note(request));
        if (substantive) {
            payment.setStatus(ConfirmationPolicy.statusFor(actorId, people.get(request.toUserId()), group));
        }
        involved.addAll(involved(payment));
        audit.record(AuditEntity.PAYMENT, payment.getId(), AuditAction.UPDATED, payment.getGroupId(), actorId,
                involved, before, Snapshots.of(payment));
        return mapper.payment(payment);
    }

    @Transactional
    public void delete(long actorId, long paymentId) {
        Payment payment = requireEditable(actorId, paymentId);
        Map<String, Object> before = Snapshots.of(payment);
        payment.markDeleted();
        audit.record(AuditEntity.PAYMENT, payment.getId(), AuditAction.DELETED, payment.getGroupId(), actorId,
                involved(payment), before, null);
    }

    /** The receiver says the money arrived: the payment now counts in balances. */
    @Transactional
    public PaymentView confirm(long actorId, long paymentId) {
        return decide(actorId, paymentId, ConfirmationStatus.CONFIRMED, AuditAction.CONFIRMED);
    }

    /** The receiver says it did not arrive. */
    @Transactional
    public PaymentView reject(long actorId, long paymentId) {
        return decide(actorId, paymentId, ConfirmationStatus.REJECTED, AuditAction.REJECTED);
    }

    private PaymentView decide(long actorId, long paymentId, ConfirmationStatus outcome, AuditAction action) {
        Payment payment = requireVisible(actorId, paymentId);
        if (!payment.getToUserId().equals(actorId)) {
            throw ApiException.forbidden("receiver_only",
                    "Only the person who received this payment can confirm or reject it");
        }
        if (payment.getStatus() != ConfirmationStatus.PENDING) {
            throw ApiException.conflict("not_pending", "This has already been answered");
        }
        Map<String, Object> before = Snapshots.of(payment);
        payment.setStatus(outcome);
        audit.record(AuditEntity.PAYMENT, payment.getId(), action, payment.getGroupId(), actorId,
                involved(payment), before, Snapshots.of(payment));
        return mapper.payment(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentView> listInGroup(long actorId, long groupId) {
        access.groupFor(groupId, actorId);
        return mapper.payments(payments.findActiveInGroup(groupId));
    }

    @Transactional(readOnly = true)
    public List<PaymentView> listDirect(long actorId) {
        return mapper.payments(payments.findActiveDirectFor(actorId));
    }

    @Transactional(readOnly = true)
    public PaymentView get(long actorId, long paymentId) {
        return mapper.payment(requireVisible(actorId, paymentId));
    }

    private Payment requireVisible(long actorId, long paymentId) {
        Payment payment = payments.findById(paymentId).filter(p -> !p.isDeleted())
                .orElseThrow(() -> ApiException.notFound("Payment"));
        access.requireCanSee(actorId, payment.getGroupId(), involved(payment));
        return payment;
    }

    private Payment requireEditable(long actorId, long paymentId) {
        Payment payment = requireVisible(actorId, paymentId);
        access.requireCanEdit(actorId, access.groupFor(payment.getGroupId(), actorId), payment.getCreatedBy());
        return payment;
    }

    private Map<Long, User> requireTwoPeople(long actorId, Group group, PaymentRequest request) {
        if (request.fromUserId().equals(request.toUserId())) {
            throw ApiException.badRequest("same_person", "Nobody can pay themselves");
        }
        return access.requireParties(actorId, group, List.of(request.fromUserId(), request.toUserId()));
    }

    private static String note(PaymentRequest request) {
        return request.note() == null || request.note().isBlank() ? null : request.note().trim();
    }

    static Set<Long> involved(Payment payment) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(payment.getFromUserId());
        ids.add(payment.getToUserId());
        ids.add(payment.getCreatedBy());
        return ids;
    }
}
