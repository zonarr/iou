package io.github.zonarr.iou.ledger;

import static io.github.zonarr.iou.ledger.DebtAndExpenseIT.assertError;
import static io.github.zonarr.iou.ledger.DebtAndExpenseIT.debtOf;
import static io.github.zonarr.iou.support.Json.bd;
import static io.github.zonarr.iou.support.Json.body;
import static io.github.zonarr.iou.support.Json.money;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import io.github.zonarr.iou.support.TestGroup;
import io.github.zonarr.iou.support.TestUser;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CustomSplitIT extends IntegrationTest {

    TestUser alice;
    TestUser bob;
    TestUser carol;
    TestGroup group;

    @BeforeEach
    void family() {
        alice = register("Alice");
        bob = register("Bob");
        carol = register("Carol");
        group = createGroup("Trip", alice, bob, carol);
        alice.api().patch(group.path(), body("autoConfirm", true)).expect(200);
    }

    @Test
    void exactAmounts() {
        JsonNode expense = alice.api().post(group.path() + "/expenses", body(
                "description", "Shopping", "amount", "100", "payerId", alice.id(), "splitType", "EXACT",
                "shares", List.of(share(alice, "20"), share(bob, "30.50"), share(carol, "49.50")))).expect(201);

        assertThat(expense.get("splitType").asString()).isEqualTo("EXACT");
        assertThat(money(debtOf(expense, bob), "thbAmount")).isEqualTo(bd("30.50"));
        assertThat(money(debtOf(expense, carol), "thbAmount")).isEqualTo(bd("49.50"));
        assertThat(shares(expense)).containsEntry(alice.id(), bd("20.00"));
    }

    @Test
    void percentages_inAForeignCurrency() {
        JsonNode expense = alice.api().post(group.path() + "/expenses", body(
                "description", "Apartment", "amount", "300", "currency", "EUR", "exchangeRate", "38.5",
                "payerId", alice.id(), "splitType", "PERCENT",
                "shares", List.of(share(alice, "50"), share(bob, "30"), share(carol, "20")))).expect(201);

        assertThat(money(expense, "thbAmount")).isEqualTo(bd("11550.00"));
        assertThat(shares(expense)).containsEntry(alice.id(), bd("150.00")).containsEntry(bob.id(), bd("90.00"))
                .containsEntry(carol.id(), bd("60.00"));
        assertThat(money(debtOf(expense, bob), "originalAmount")).isEqualTo(bd("90.00"));
        assertThat(money(debtOf(expense, bob), "thbAmount")).isEqualTo(bd("3465.00"));
        assertThat(money(debtOf(expense, carol), "thbAmount")).isEqualTo(bd("2310.00"));
        // The percentage typed in comes back, so the expense can be edited later.
        for (JsonNode s : expense.get("shares")) {
            if (s.get("user").get("id").asLong() == bob.id()) {
                assertThat(s.get("weight").decimalValue()).isEqualByComparingTo("30");
            }
        }
    }

    @Test
    void shares_twoAdultsAndAChild() {
        JsonNode expense = alice.api().post(group.path() + "/expenses", body(
                "description", "Tickets", "amount", "100", "payerId", carol.id(), "splitType", "SHARES",
                "shares", List.of(share(alice, "2"), share(bob, "2"), share(carol, "1")))).expect(201);

        // 100 by 2:2:1 = 40, 40, 20.
        assertThat(money(debtOf(expense, alice), "thbAmount")).isEqualTo(bd("40.00"));
        assertThat(money(debtOf(expense, bob), "thbAmount")).isEqualTo(bd("40.00"));
        assertThat(shares(expense)).containsEntry(carol.id(), bd("20.00"));
    }

    @Test
    void severalPayers() {
        // 100 split equally between three (33.34 / 33.33 / 33.33); Alice paid 60 and Bob 40.
        JsonNode expense = carol.api().post(group.path() + "/expenses", body(
                "description", "Dinner", "amount", "100",
                "payers", List.of(payer(alice, "60"), payer(bob, "40")),
                "participantIds", List.of(alice.id(), bob.id(), carol.id()))).expect(201);

        assertThat(expense.get("payers")).hasSize(2);
        assertThat(expense.get("debts")).hasSize(2);
        Map<Long, BigDecimal> owedTo = new HashMap<>();
        for (JsonNode debt : expense.get("debts")) {
            assertThat(debt.get("debtor").get("id").asLong()).isEqualTo(carol.id());
            owedTo.put(debt.get("creditor").get("id").asLong(), money(debt, "thbAmount"));
        }
        assertThat(owedTo).containsEntry(alice.id(), bd("26.66")).containsEntry(bob.id(), bd("6.67"));

        Map<Long, BigDecimal> balances = new HashMap<>();
        alice.api().get(group.path() + "/balances").expect(200).get("balances")
                .forEach(b -> balances.put(b.get("user").get("id").asLong(), money(b, "net")));
        assertThat(balances).containsEntry(alice.id(), bd("26.66")).containsEntry(bob.id(), bd("6.67"))
                .containsEntry(carol.id(), bd("-33.33"));
    }

    @Test
    void severalPayersWithACustomSplit_inAForeignCurrency_stillBalancesToZero() {
        alice.api().post(group.path() + "/expenses", body(
                "description", "Ski passes", "amount", "333.35", "currency", "CHF", "exchangeRate", "43.2109",
                "payers", List.of(payer(alice, "200"), payer(carol, "133.35")),
                "splitType", "SHARES",
                "shares", List.of(share(alice, "1"), share(bob, "1.5"), share(carol, "1")))).expect(201);

        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode b : alice.api().get(group.path() + "/balances").expect(200).get("balances")) {
            sum = sum.add(money(b, "net"));
        }
        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    void editingCanChangeTheSplitTypeAndThePayers() {
        long id = alice.api().post(group.path() + "/expenses", body(
                "description", "Dinner", "amount", "90", "payerId", alice.id(),
                "participantIds", List.of(alice.id(), bob.id(), carol.id()))).expect(201).get("id").asLong();

        JsonNode edited = alice.api().put("/api/expenses/" + id, body(
                "description", "Dinner", "amount", "90",
                "payers", List.of(payer(alice, "45"), payer(bob, "45")),
                "splitType", "EXACT",
                "shares", List.of(share(alice, "10"), share(bob, "20"), share(carol, "60")))).expect(200);

        assertThat(edited.get("splitType").asString()).isEqualTo("EXACT");
        assertThat(edited.get("payers")).hasSize(2);
        // Alice paid 45 for a 10 share (+35), Bob 45 for 20 (+25), Carol owes 60: 35 to Alice, 25 to Bob.
        Map<Long, BigDecimal> owedTo = new HashMap<>();
        for (JsonNode debt : edited.get("debts")) {
            assertThat(debt.get("debtor").get("id").asLong()).isEqualTo(carol.id());
            owedTo.put(debt.get("creditor").get("id").asLong(), money(debt, "thbAmount"));
        }
        assertThat(owedTo).containsOnly(Map.entry(alice.id(), bd("35.00")), Map.entry(bob.id(), bd("25.00")));
        assertThat(alice.api().get(group.path() + "/debts").expect(200)).hasSize(2); // Bob's old debt is gone
    }

    @Test
    void validation() {
        String path = group.path() + "/expenses";
        assertError(alice.api().post(path, body("description", "x", "amount", "100", "payerId", alice.id(),
                "splitType", "EXACT", "shares", List.of(share(alice, "20"), share(bob, "30")))).expect(400),
                "split_mismatch");
        assertError(alice.api().post(path, body("description", "x", "amount", "100", "payerId", alice.id(),
                "splitType", "PERCENT", "shares", List.of(share(alice, "60"), share(bob, "30")))).expect(400),
                "percent_mismatch");
        assertError(alice.api().post(path, body("description", "x", "amount", "100", "payerId", alice.id(),
                "splitType", "SHARES", "shares", List.of(share(alice, "1"), share(bob, "0")))).expect(400),
                "split_value_required");
        assertError(alice.api().post(path, body("description", "x", "amount", "100", "payerId", alice.id(),
                "splitType", "PERCENT", "participantIds", List.of(alice.id(), bob.id()))).expect(400),
                "split_value_required");
        assertError(alice.api().post(path, body("description", "x", "amount", "100",
                "payers", List.of(payer(alice, "60"), payer(bob, "30")),
                "participantIds", List.of(alice.id(), bob.id(), carol.id()))).expect(400), "payers_mismatch");
        assertError(alice.api().post(path, body("description", "x", "amount", "100", "payerId", alice.id(),
                "payers", List.of(payer(alice, "100")),
                "participantIds", List.of(bob.id()))).expect(400), "payers_conflict");
        assertError(alice.api().post(path, body("description", "x", "amount", "100", "payerId", alice.id(),
                "participantIds", List.of(bob.id()), "shares", List.of(share(bob, "1")))).expect(400),
                "participants_conflict");
        assertError(alice.api().post(path, body("description", "x", "amount", "100", "payerId", alice.id(),
                "splitType", "NONSENSE", "participantIds", List.of(bob.id()))).expect(400), "bad_request");
        assertThat(alice.api().get(path).expect(200)).isEmpty();
    }

    private static Map<String, Object> share(TestUser user, String value) {
        return body("userId", user.id(), "value", value);
    }

    private static Map<String, Object> payer(TestUser user, String amount) {
        return body("userId", user.id(), "amount", amount);
    }

    private static Map<Long, BigDecimal> shares(JsonNode expense) {
        Map<Long, BigDecimal> map = new HashMap<>();
        expense.get("shares").forEach(s -> map.put(s.get("user").get("id").asLong(), money(s, "originalAmount")));
        return map;
    }
}
