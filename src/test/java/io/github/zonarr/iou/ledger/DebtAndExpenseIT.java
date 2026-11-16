package io.github.zonarr.iou.ledger;

import static io.github.zonarr.iou.support.Json.bd;
import static io.github.zonarr.iou.support.Json.body;
import static io.github.zonarr.iou.support.Json.money;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import io.github.zonarr.iou.support.TestGroup;
import io.github.zonarr.iou.support.TestUser;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DebtAndExpenseIT extends IntegrationTest {

    // ---- simple IOUs ----

    @Test
    void debtorRecordingTheirOwnDebtIsConfirmedAtOnce() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);

        JsonNode debt = alice.api().post(group.path() + "/debts", body(
                "debtorId", alice.id(), "creditorId", bob.id(), "amount", 200, "description", "Taxi")).expect(201);

        assertThat(debt.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(debt.get("debtor").get("displayName").asString()).isEqualTo("Alice");
        assertThat(debt.get("creditor").get("displayName").asString()).isEqualTo("Bob");
        assertThat(money(debt, "originalAmount")).isEqualTo(bd("200.00"));
        assertThat(money(debt, "thbAmount")).isEqualTo(bd("200.00"));
        assertThat(debt.get("currency").asString()).isEqualTo("THB");
        assertThat(money(debt, "exchangeRate")).isEqualByComparingTo("1");
        assertThat(debt.get("groupId").asLong()).isEqualTo(group.id());
    }

    @Test
    void debtRecordedBySomeoneElseIsPending() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup group = createGroup("Trip", alice, bob, carol);

        JsonNode byCreditor = bob.api().post(group.path() + "/debts", body(
                "debtorId", alice.id(), "creditorId", bob.id(), "amount", "200.00", "description", "Taxi")).expect(201);
        JsonNode byThirdParty = carol.api().post(group.path() + "/debts", body(
                "debtorId", alice.id(), "creditorId", bob.id(), "amount", "50", "description", "Snacks")).expect(201);

        assertThat(byCreditor.get("status").asString()).isEqualTo("PENDING");
        assertThat(byThirdParty.get("status").asString()).isEqualTo("PENDING");
        assertThat(byThirdParty.get("createdBy").get("displayName").asString()).isEqualTo("Carol");
    }

    @Test
    void foreignCurrencyDebtStoresOriginalRateAndThb() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);

        JsonNode debt = alice.api().post(group.path() + "/debts", body(
                "debtorId", alice.id(), "creditorId", bob.id(), "amount", "33.33", "currency", "eur",
                "exchangeRate", "38.5", "description", "Museum")).expect(201);

        assertThat(money(debt, "originalAmount")).isEqualTo(bd("33.33"));
        assertThat(debt.get("currency").asString()).isEqualTo("EUR");
        assertThat(money(debt, "exchangeRate")).isEqualByComparingTo("38.5");
        assertThat(money(debt, "thbAmount")).isEqualTo(bd("1283.21")); // 1283.205 rounds half-up
    }

    @Test
    void debtValidation() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser outsider = register("Olga");
        TestGroup group = createGroup("Trip", alice, bob);
        String path = group.path() + "/debts";

        assertError(alice.api().post(path, body("debtorId", alice.id(), "creditorId", alice.id(),
                "amount", 5, "description", "x")).expect(400), "same_person");
        assertError(alice.api().post(path, body("debtorId", alice.id(), "creditorId", outsider.id(),
                "amount", 5, "description", "x")).expect(400), "not_a_member");
        assertError(alice.api().post(path, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 0, "description", "x")).expect(400), "amount_not_positive");
        assertError(alice.api().post(path, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", "1.005", "description", "x")).expect(400), "amount_too_precise");
        assertError(alice.api().post(path, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 5, "currency", "EUR", "description", "x")).expect(400), "rate_required");
        assertError(alice.api().post(path, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 5, "currency", "ZZZ", "exchangeRate", 2, "description", "x")).expect(400), "unknown_currency");
        assertError(alice.api().post(path, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 5, "description", " ")).expect(400), "validation_failed");
        outsider.api().post(path, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 5, "description", "x")).expect(404);
    }

    @Test
    void oneOnOneDebtOutsideAnyGroup() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");

        long bobId = alice.api().get("/api/users/lookup?email=" + bob.email()).expect(200).get("id").asLong();
        JsonNode iOwe = alice.api().post("/api/debts", body(
                "debtorId", alice.id(), "creditorId", bobId, "amount", 200, "description", "Lunch")).expect(201);
        JsonNode owesMe = alice.api().post("/api/debts", body(
                "debtorId", bobId, "creditorId", alice.id(), "amount", 80, "description", "Coffee")).expect(201);

        assertThat(bobId).isEqualTo(bob.id());
        assertThat(iOwe.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(iOwe.get("groupId").isNull()).isTrue();
        assertThat(owesMe.get("status").asString()).isEqualTo("PENDING");
        // Both parties see them; nobody else does.
        assertThat(alice.api().get("/api/debts").expect(200)).hasSize(2);
        assertThat(bob.api().get("/api/debts").expect(200)).hasSize(2);
        assertThat(carol.api().get("/api/debts").expect(200)).isEmpty();
        carol.api().get("/api/debts/" + iOwe.get("id").asLong()).expect(404);
        bob.api().get("/api/debts/" + iOwe.get("id").asLong()).expect(200);
        // You cannot record a debt between two other people outside a group.
        assertError(carol.api().post("/api/debts", body(
                "debtorId", alice.id(), "creditorId", bob.id(), "amount", 5, "description", "x")).expect(400),
                "must_involve_you");
        alice.api().get("/api/users/lookup?email=ghost@example.com").expect(404);
    }

    @Test
    void placeholdersCannotBeUsedOutsideGroups() {
        TestUser alice = register("Alice");
        TestGroup group = createGroup("Trip", alice);
        long mum = addPlaceholder(alice, group, "Mum");

        alice.api().post("/api/debts", body(
                "debtorId", alice.id(), "creditorId", mum, "amount", 5, "description", "x")).expect(404);
    }

    // ---- equal-split expenses ----

    @Test
    void readmeExample_alicePays900ForThree() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup group = createGroup("Dinner club", alice, bob, carol);

        JsonNode expense = alice.api().post(group.path() + "/expenses", body(
                "description", "Dinner", "amount", 900, "payerId", alice.id(),
                "participantIds", List.of(alice.id(), bob.id(), carol.id()))).expect(201);

        assertThat(money(expense, "thbAmount")).isEqualTo(bd("900.00"));
        assertThat(expense.get("splitType").asString()).isEqualTo("EQUAL");
        assertThat(expense.get("payers")).hasSize(1);
        assertThat(expense.get("shares")).hasSize(3);
        assertThat(expense.get("debts")).hasSize(2);
        for (JsonNode debt : expense.get("debts")) {
            assertThat(debt.get("creditor").get("id").asLong()).isEqualTo(alice.id());
            assertThat(money(debt, "thbAmount")).isEqualTo(bd("300.00"));
            assertThat(debt.get("description").asString()).isEqualTo("Dinner");
            assertThat(debt.get("status").asString()).isEqualTo("PENDING"); // recorded by Alice, not the debtor
            assertThat(debt.get("expenseId").asLong()).isEqualTo(expense.get("id").asLong());
        }
        assertThat(bob.api().get(group.path() + "/expenses").expect(200)).hasSize(1);
        assertThat(bob.api().get(group.path() + "/debts").expect(200)).hasSize(2);
        assertThat(expense.get("spentOn").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void unevenAmount_sharesAddUpAndRecorderWhoIsDebtorIsConfirmed() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup group = createGroup("Trip", alice, bob, carol);

        // Bob records that Carol paid 100 for all three: Bob's own debt is confirmed, Alice's is pending.
        JsonNode expense = bob.api().post(group.path() + "/expenses", body(
                "description", "Groceries", "amount", "100.00", "payerId", carol.id(),
                "participantIds", List.of(carol.id(), bob.id(), alice.id()), "spentOn", "2026-12-17")).expect(201);

        BigDecimal shareTotal = BigDecimal.ZERO;
        for (JsonNode share : expense.get("shares")) {
            shareTotal = shareTotal.add(money(share, "originalAmount"));
        }
        assertThat(shareTotal).isEqualTo(bd("100.00"));
        JsonNode aliceDebt = debtOf(expense, alice);
        JsonNode bobDebt = debtOf(expense, bob);
        assertThat(money(aliceDebt, "thbAmount")).isEqualTo(bd("33.34")); // lowest id carries the extra satang
        assertThat(money(bobDebt, "thbAmount")).isEqualTo(bd("33.33"));
        assertThat(aliceDebt.get("status").asString()).isEqualTo("PENDING");
        assertThat(bobDebt.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(expense.get("spentOn").asString()).isEqualTo("2026-12-17");
    }

    @Test
    void foreignCurrencyExpense_keepsOriginalAndThbOnEveryLine() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup group = createGroup("Paris", alice, bob, carol);

        JsonNode expense = alice.api().post(group.path() + "/expenses", body(
                "description", "Dinner in Paris", "amount", "100.00", "currency", "EUR", "exchangeRate", "38.50",
                "payerId", alice.id(), "participantIds", List.of(alice.id(), bob.id(), carol.id()))).expect(201);

        assertThat(money(expense, "originalAmount")).isEqualTo(bd("100.00"));
        assertThat(expense.get("currency").asString()).isEqualTo("EUR");
        assertThat(money(expense, "exchangeRate")).isEqualByComparingTo("38.5");
        assertThat(money(expense, "thbAmount")).isEqualTo(bd("3850.00"));
        JsonNode bobDebt = debtOf(expense, bob);
        JsonNode carolDebt = debtOf(expense, carol);
        assertThat(money(bobDebt, "originalAmount")).isEqualTo(bd("33.33"));
        assertThat(bobDebt.get("currency").asString()).isEqualTo("EUR");
        assertThat(money(bobDebt, "thbAmount")).isEqualTo(bd("1283.21"));
        assertThat(money(carolDebt, "thbAmount")).isEqualTo(bd("1283.20"));
        BigDecimal thbShares = BigDecimal.ZERO;
        for (JsonNode share : expense.get("shares")) {
            thbShares = thbShares.add(money(share, "thbAmount"));
        }
        assertThat(thbShares).isEqualTo(bd("3850.00"));
        assertThat(money(expense.get("payers").get(0), "thbAmount")).isEqualTo(bd("3850.00"));
    }

    @Test
    void rateIsRememberedPerCurrencyPerGroup() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        TestGroup other = createGroup("Other", alice, bob);
        assertThat(alice.api().get(group.path() + "/rates").expect(200)).isEmpty();

        alice.api().post(group.path() + "/expenses", body("description", "A", "amount", 10, "currency", "EUR",
                "exchangeRate", "38.1", "payerId", alice.id(), "participantIds", List.of(bob.id()))).expect(201);
        alice.api().post(group.path() + "/debts", body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 10, "currency", "CHF", "exchangeRate", "43", "description", "B")).expect(201);
        bob.api().post(group.path() + "/expenses", body("description", "C", "amount", 10, "currency", "EUR",
                "exchangeRate", "38.75", "payerId", bob.id(), "participantIds", List.of(alice.id()))).expect(201);
        alice.api().post(group.path() + "/expenses", body("description", "D", "amount", 10,
                "payerId", alice.id(), "participantIds", List.of(bob.id()))).expect(201);

        JsonNode rates = bob.api().get(group.path() + "/rates").expect(200);
        assertThat(rates.propertyNames()).containsExactlyInAnyOrder("EUR", "CHF"); // THB is never listed
        assertThat(rates.get("EUR").decimalValue()).isEqualByComparingTo("38.75");  // the latest one
        assertThat(rates.get("CHF").decimalValue()).isEqualByComparingTo("43");
        assertThat(alice.api().get(other.path() + "/rates").expect(200)).isEmpty();
        assertThat(alice.api().get("/api/me/rates").expect(200).get("EUR").decimalValue())
                .isEqualByComparingTo("38.1"); // the last one Alice herself entered
    }

    @Test
    void placeholderDebtorIsConfirmedAutomatically() {
        TestUser alice = register("Alice");
        TestGroup group = createGroup("Trip", alice);
        long mum = addPlaceholder(alice, group, "Mum");

        JsonNode expense = alice.api().post(group.path() + "/expenses", body(
                "description", "Tickets", "amount", 60, "payerId", alice.id(),
                "participantIds", List.of(alice.id(), mum))).expect(201);

        assertThat(expense.get("debts")).hasSize(1);
        assertThat(expense.get("debts").get(0).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(expense.get("debts").get(0).get("debtor").get("placeholder").asBoolean()).isTrue();
    }

    @Test
    void trustedGroupConfirmsEverythingAtOnce() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Family", alice, bob);
        alice.api().patch(group.path(), body("autoConfirm", true)).expect(200);

        JsonNode expense = alice.api().post(group.path() + "/expenses", body(
                "description", "Dinner", "amount", 60, "payerId", alice.id(),
                "participantIds", List.of(alice.id(), bob.id()))).expect(201);
        JsonNode debt = alice.api().post(group.path() + "/debts", body(
                "debtorId", bob.id(), "creditorId", alice.id(), "amount", 5, "description", "Water")).expect(201);

        assertThat(expense.get("debts").get(0).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(debt.get("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void expenseValidation() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser outsider = register("Olga");
        TestGroup group = createGroup("Trip", alice, bob);
        String path = group.path() + "/expenses";

        assertError(alice.api().post(path, body("description", "x", "amount", 10, "payerId", alice.id(),
                "participantIds", List.of())).expect(400), "participants_required");
        assertError(alice.api().post(path, body("description", "x", "amount", 10,
                "participantIds", List.of(bob.id()))).expect(400), "payers_required");
        assertError(alice.api().post(path, body("description", "x", "amount", 10, "payerId", alice.id(),
                "participantIds", List.of(alice.id()))).expect(400), "nothing_to_split");
        assertError(alice.api().post(path, body("description", "x", "amount", 10, "payerId", alice.id(),
                "participantIds", List.of(bob.id(), bob.id()))).expect(400), "duplicate_participant");
        assertError(alice.api().post(path, body("description", "x", "amount", 10, "payerId", alice.id(),
                "participantIds", List.of(bob.id(), outsider.id()))).expect(400), "not_a_member");
        assertError(alice.api().post(path, body("description", "x", "amount", 10, "payerId", outsider.id(),
                "participantIds", List.of(bob.id()))).expect(400), "not_a_member");
        assertThat(alice.api().get(path).expect(200)).isEmpty(); // nothing was half-saved
        assertThat(alice.api().get(group.path() + "/debts").expect(200)).isEmpty();
    }

    @Test
    void oneOnOneExpenseOutsideAnyGroup() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");

        JsonNode expense = alice.api().post("/api/expenses", body(
                "description", "Shared taxi", "amount", "75.25", "payerId", bob.id(),
                "participantIds", List.of(alice.id(), bob.id()))).expect(201);

        assertThat(expense.get("groupId").isNull()).isTrue();
        assertThat(expense.get("debts")).hasSize(1);
        assertThat(money(expense.get("debts").get(0), "thbAmount")).isEqualTo(bd("37.63"));
        assertThat(expense.get("debts").get(0).get("status").asString()).isEqualTo("CONFIRMED"); // Alice is the debtor
        assertThat(bob.api().get("/api/expenses").expect(200)).hasSize(1);
        assertThat(carol.api().get("/api/expenses").expect(200)).isEmpty();
        carol.api().get("/api/expenses/" + expense.get("id").asLong()).expect(404);
        assertError(carol.api().post("/api/expenses", body("description", "x", "amount", 10, "payerId", bob.id(),
                "participantIds", List.of(alice.id(), bob.id()))).expect(400), "must_involve_you");
    }

    @Test
    void currencyListFeaturesTheTripCurrencies() {
        TestUser alice = register("Alice");

        JsonNode currencies = alice.api().get("/api/currencies").expect(200);

        assertThat(currencies.get("base").asString()).isEqualTo("THB");
        assertThat(currencies.get("featured").toString()).isEqualTo("[\"THB\",\"EUR\",\"CHF\"]");
        assertThat(currencies.get("all").size()).isGreaterThan(100);
    }

    static JsonNode debtOf(JsonNode expense, TestUser debtor) {
        for (JsonNode debt : expense.get("debts")) {
            if (debt.get("debtor").get("id").asLong() == debtor.id()) {
                return debt;
            }
        }
        throw new AssertionError("no debt for " + debtor.name());
    }

    static void assertError(JsonNode body, String code) {
        assertThat(body.get("error").asString()).isEqualTo(code);
    }
}
