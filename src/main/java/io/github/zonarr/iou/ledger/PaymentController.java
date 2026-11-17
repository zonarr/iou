package io.github.zonarr.iou.ledger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.zonarr.iou.auth.Actor;
import io.github.zonarr.iou.ledger.LedgerDtos.PaymentRequest;
import io.github.zonarr.iou.ledger.LedgerDtos.PaymentView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payments", description = "Repayments, confirmed by the receiver")
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** Records a repayment. Put {@code groupId} in the body for a payment inside a group. */
    @Operation(summary = "Record a repayment (put groupId in the body for a group payment)")
    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentView create(Actor actor, @Valid @RequestBody PaymentRequest request) {
        return payments.create(actor.id(), request);
    }

    /** My payments outside groups. */
    @Operation(summary = "My payments outside groups")
    @GetMapping("/payments")
    public List<PaymentView> listDirect(Actor actor) {
        return payments.listDirect(actor.id());
    }

    @Operation(summary = "Payments in a group")
    @GetMapping("/groups/{groupId}/payments")
    public List<PaymentView> listInGroup(Actor actor, @PathVariable long groupId) {
        return payments.listInGroup(actor.id(), groupId);
    }

    @Operation(summary = "One payment")
    @GetMapping("/payments/{id}")
    public PaymentView get(Actor actor, @PathVariable long id) {
        return payments.get(actor.id(), id);
    }

    /** Changes a payment. Changing who or how much sends it back to pending. The group cannot change. */
    @Operation(summary = "Change a payment (changing who or how much sends it back to pending)")
    @PutMapping("/payments/{id}")
    public PaymentView update(Actor actor, @PathVariable long id, @Valid @RequestBody PaymentRequest request) {
        return payments.update(actor.id(), id, request);
    }

    @Operation(summary = "Delete a payment")
    @DeleteMapping("/payments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Actor actor, @PathVariable long id) {
        payments.delete(actor.id(), id);
    }

    /** Creditor confirms a payment was received. */
    @Operation(summary = "Receiver confirms the payment arrived")
    @PostMapping("/payments/{id}/confirm")
    public PaymentView confirm(Actor actor, @PathVariable long id) {
        return payments.confirm(actor.id(), id);
    }

    /** Creditor says the payment did not arrive. */
    @Operation(summary = "Receiver says the payment did not arrive")
    @PostMapping("/payments/{id}/reject")
    public PaymentView reject(Actor actor, @PathVariable long id) {
        return payments.reject(actor.id(), id);
    }
}
