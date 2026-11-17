package io.github.zonarr.iou.ledger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.zonarr.iou.auth.Actor;
import io.github.zonarr.iou.ledger.LedgerDtos.AcceptAllRequest;
import io.github.zonarr.iou.ledger.LedgerDtos.AcceptAllResult;
import io.github.zonarr.iou.ledger.LedgerDtos.PendingView;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in person's inbox: debts to accept and payments to confirm. */
@Tag(name = "Pending", description = "What is waiting for my answer")
@RestController
@RequestMapping("/api/me/pending")
public class PendingController {

    private final DebtRepository debts;
    private final PaymentRepository payments;
    private final DebtService debtService;
    private final PaymentService paymentService;
    private final LedgerMapper mapper;

    public PendingController(DebtRepository debts, PaymentRepository payments, DebtService debtService,
            PaymentService paymentService, LedgerMapper mapper) {
        this.debts = debts;
        this.payments = payments;
        this.debtService = debtService;
        this.paymentService = paymentService;
        this.mapper = mapper;
    }

    @Operation(summary = "Debts for me to accept and payments for me to confirm")
    @GetMapping
    @Transactional(readOnly = true)
    public PendingView pending(Actor actor) {
        return new PendingView(mapper.debts(debts.findAwaiting(actor.id())),
                mapper.payments(payments.findAwaiting(actor.id())));
    }

    /** Accepts every debt and confirms every payment waiting for me (optionally only one group's). */
    @Operation(summary = "Accept and confirm everything waiting for me (optionally one group only)")
    @PostMapping("/accept-all")
    @Transactional
    public AcceptAllResult acceptAll(Actor actor, @RequestBody(required = false) AcceptAllRequest request) {
        Long groupId = request == null ? null : request.groupId();
        List<Debt> myDebts = debts.findAwaiting(actor.id()).stream()
                .filter(d -> groupId == null || groupId.equals(d.getGroupId())).toList();
        List<Payment> myPayments = payments.findAwaiting(actor.id()).stream()
                .filter(p -> groupId == null || groupId.equals(p.getGroupId())).toList();
        myDebts.forEach(d -> debtService.accept(actor.id(), d.getId()));
        myPayments.forEach(p -> paymentService.confirm(actor.id(), p.getId()));
        return new AcceptAllResult(myDebts.size(), myPayments.size());
    }
}
