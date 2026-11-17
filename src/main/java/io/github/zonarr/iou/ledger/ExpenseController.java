package io.github.zonarr.iou.ledger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.zonarr.iou.auth.Actor;
import io.github.zonarr.iou.ledger.LedgerDtos.ExpenseRequest;
import io.github.zonarr.iou.ledger.LedgerDtos.ExpenseView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Expenses", description = "Shared expenses and how they are split")
@RestController
@RequestMapping("/api")
public class ExpenseController {

    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    /** Records an expense in a group and creates the debts that follow from it. */
    @Operation(summary = "Record an expense in a group")
    @PostMapping("/groups/{groupId}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView createInGroup(Actor actor, @PathVariable long groupId,
            @Valid @RequestBody ExpenseRequest request) {
        return expenses.create(actor.id(), groupId, request);
    }

    @Operation(summary = "Expenses in a group")
    @GetMapping("/groups/{groupId}/expenses")
    public List<ExpenseView> listInGroup(Actor actor, @PathVariable long groupId) {
        return expenses.listInGroup(actor.id(), groupId);
    }

    /** Records an expense shared with other registered people, outside any group. */
    @Operation(summary = "Record an expense shared outside any group")
    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView createDirect(Actor actor, @Valid @RequestBody ExpenseRequest request) {
        return expenses.create(actor.id(), null, request);
    }

    /** My expenses outside groups. */
    @Operation(summary = "My expenses outside groups")
    @GetMapping("/expenses")
    public List<ExpenseView> listDirect(Actor actor) {
        return expenses.listDirect(actor.id());
    }

    @Operation(summary = "One expense with payers, shares and debts")
    @GetMapping("/expenses/{id}")
    public ExpenseView get(Actor actor, @PathVariable long id) {
        return expenses.get(actor.id(), id);
    }

    /** Replaces the expense. Debts whose amount changes go back to pending. */
    @Operation(summary = "Replace an expense (debts whose amount changes go back to pending)")
    @PutMapping("/expenses/{id}")
    public ExpenseView update(Actor actor, @PathVariable long id, @Valid @RequestBody ExpenseRequest request) {
        return expenses.update(actor.id(), id, request);
    }

    /** Deletes the expense and the debts it produced. */
    @Operation(summary = "Delete an expense and the debts it produced")
    @DeleteMapping("/expenses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Actor actor, @PathVariable long id) {
        expenses.delete(actor.id(), id);
    }
}
