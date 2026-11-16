package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.auth.Actor;
import io.github.zonarr.iou.ledger.LedgerDtos.DebtRequest;
import io.github.zonarr.iou.ledger.LedgerDtos.DebtView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DebtController {

    private final DebtService debts;

    public DebtController(DebtService debts) {
        this.debts = debts;
    }

    /** Records a simple IOU between two members of a group. */
    @PostMapping("/groups/{groupId}/debts")
    @ResponseStatus(HttpStatus.CREATED)
    public DebtView createInGroup(Actor actor, @PathVariable long groupId, @Valid @RequestBody DebtRequest request) {
        return debts.create(actor.id(), groupId, request);
    }

    /** All debts in the group: standalone IOUs and the ones generated from expenses. */
    @GetMapping("/groups/{groupId}/debts")
    public List<DebtView> listInGroup(Actor actor, @PathVariable long groupId) {
        return debts.listInGroup(actor.id(), groupId);
    }

    /** Records a one-on-one IOU (no group). You must be the debtor or the creditor. */
    @PostMapping("/debts")
    @ResponseStatus(HttpStatus.CREATED)
    public DebtView createDirect(Actor actor, @Valid @RequestBody DebtRequest request) {
        return debts.create(actor.id(), null, request);
    }

    /** My one-on-one debts (no group). */
    @GetMapping("/debts")
    public List<DebtView> listDirect(Actor actor) {
        return debts.listDirect(actor.id());
    }

    @GetMapping("/debts/{id}")
    public DebtView get(Actor actor, @PathVariable long id) {
        return debts.get(actor.id(), id);
    }
}
