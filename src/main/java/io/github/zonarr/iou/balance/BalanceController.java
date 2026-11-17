package io.github.zonarr.iou.balance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.zonarr.iou.auth.Actor;
import io.github.zonarr.iou.balance.BalanceDtos.GroupBalances;
import io.github.zonarr.iou.balance.BalanceDtos.GroupSettleUp;
import io.github.zonarr.iou.balance.BalanceDtos.MySettleUp;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Balances", description = "Net balances and settle-up suggestions, in THB")
@RestController
@RequestMapping("/api")
public class BalanceController {

    private final BalanceService balances;

    public BalanceController(BalanceService balances) {
        this.balances = balances;
    }

    /** Net balance per member, in THB, from confirmed debts and payments only. */
    @Operation(summary = "Net balance per member (confirmed entries only)")
    @GetMapping("/groups/{id}/balances")
    public GroupBalances groupBalances(Actor actor, @PathVariable long id) {
        return balances.groupBalances(actor.id(), id);
    }

    /** Suggested payment plan for a group with as few transfers as possible. Advisory only. */
    @Operation(summary = "Suggested minimal payment plan for a group (advisory only)")
    @GetMapping("/groups/{id}/settle-up")
    public GroupSettleUp groupSettleUp(Actor actor, @PathVariable long id) {
        return balances.groupSettleUp(actor.id(), id);
    }

    /** Suggested plan across all of my balances: every group plus one-on-one debts. */
    @Operation(summary = "Suggested plan across all of my balances")
    @GetMapping("/me/settle-up")
    public MySettleUp mySettleUp(Actor actor) {
        return balances.mySettleUp(actor.id());
    }
}
