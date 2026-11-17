package io.github.zonarr.iou.balance;

import static io.github.zonarr.iou.support.Json.bd;
import static io.github.zonarr.iou.support.Json.body;
import static io.github.zonarr.iou.support.Json.money;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import io.github.zonarr.iou.support.TestGroup;
import io.github.zonarr.iou.support.TestUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class BalanceIT extends IntegrationTest {

    @Test
    void readmeExample_threeDebtsBecomeOnePayment() {
        TestUser a = register("Ann");
        TestUser b = register("Ben");
        TestUser c = register("Cat");
        TestGroup group = createGroup("Readme", a, b, c);
        // A owes B 100, B owes C 100, A owes C 50: each recorded by its debtor, so confirmed.
        iou(a, group, a, b, "100");
        iou(b, group, b, c, "100");
        iou(a, group, a, c, "50");

        Map<Long, BigDecimal> balances = balances(a, group);
        JsonNode plan = a.api().get(group.path() + "/settle-up").expect(200);

        assertThat(balances).containsEntry(a.id(), bd("-150.00")).containsEntry(b.id(), bd("0.00"))
                .containsEntry(c.id(), bd("150.00"));
        assertThat(plan.get("currency").asString()).isEqualTo("THB");
        assertThat(plan.get("transfers")).hasSize(1);
        JsonNode transfer = plan.get("transfers").get(0);
        assertThat(transfer.get("from").get("displayName").asString()).isEqualTo("Ann");
        assertThat(transfer.get("to").get("displayName").asString()).isEqualTo("Cat");
        assertThat(money(transfer, "amount")).isEqualTo(bd("150.00"));
    }

    @Test
    void emptyGroupHasZeroBalancesAndNothingToSettle() {
        TestUser a = register("Ann");
        TestUser b = register("Ben");
        TestGroup group = createGroup("Empty", a, b);

        JsonNode response = a.api().get(group.path() + "/balances").expect(200);

        assertThat(response.get("balances")).hasSize(2);
        assertThat(balances(a, group).values()).allSatisfy(v -> assertThat(v).isEqualTo(bd("0.00")));
        assertThat(response.get("pendingDebts").asInt()).isZero();
        assertThat(a.api().get(group.path() + "/settle-up").expect(200).get("transfers")).isEmpty();
    }

    @Test
    void pendingRejectedAndDeletedDebtsAreNotCounted() {
        TestUser a = register("Ann");
        TestUser b = register("Ben");
        TestGroup group = createGroup("Trip", a, b);
        long pending = iou(b, group, a, b, "100");   // recorded by the creditor: pending
        long rejected = iou(b, group, a, b, "70");
        long deleted = iou(a, group, a, b, "30");     // confirmed, then deleted
        a.api().post("/api/debts/" + rejected + "/reject").expect(200);
        a.api().delete("/api/debts/" + deleted).expect(204);

        JsonNode response = a.api().get(group.path() + "/balances").expect(200);
        assertThat(balances(a, group).values()).allSatisfy(v -> assertThat(v).isEqualTo(bd("0.00")));
        assertThat(response.get("pendingDebts").asInt()).isEqualTo(1);
        assertThat(a.api().get(group.path() + "/settle-up").expect(200).get("transfers")).isEmpty();

        a.api().post("/api/debts/" + pending + "/accept").expect(200);
        assertThat(balances(a, group)).containsEntry(a.id(), bd("-100.00")).containsEntry(b.id(), bd("100.00"));
        assertThat(a.api().get(group.path() + "/balances").expect(200).get("pendingDebts").asInt()).isZero();
    }

    @Test
    void paymentsChangeBalancesOnlyOnceConfirmed_andSettleUpNeverChangesAnything() {
        TestUser a = register("Ann");
        TestUser b = register("Ben");
        TestGroup group = createGroup("Trip", a, b);
        iou(a, group, a, b, "100");

        // Looking at the suggestion twice changes nothing.
        a.api().get(group.path() + "/settle-up").expect(200);
        assertThat(money(a.api().get(group.path() + "/settle-up").expect(200).get("transfers").get(0), "amount"))
                .isEqualTo(bd("100.00"));
        assertThat(a.api().get(group.path() + "/debts").expect(200)).hasSize(1);

        long payment = a.api().post("/api/payments", body("groupId", group.id(), "fromUserId", a.id(),
                "toUserId", b.id(), "amount", "60")).expect(201).get("id").asLong();
        assertThat(balances(a, group)).containsEntry(a.id(), bd("-100.00")); // still pending
        assertThat(a.api().get(group.path() + "/balances").expect(200).get("pendingPayments").asInt()).isEqualTo(1);

        b.api().post("/api/payments/" + payment + "/confirm").expect(200);
        assertThat(balances(a, group)).containsEntry(a.id(), bd("-40.00")).containsEntry(b.id(), bd("40.00"));

        long rest = a.api().post("/api/payments", body("groupId", group.id(), "fromUserId", a.id(),
                "toUserId", b.id(), "amount", "40")).expect(201).get("id").asLong();
        b.api().post("/api/payments/" + rest + "/confirm").expect(200);
        assertThat(balances(a, group).values()).allSatisfy(v -> assertThat(v).isEqualTo(bd("0.00")));
        assertThat(a.api().get(group.path() + "/settle-up").expect(200).get("transfers")).isEmpty();
    }

    @Test
    void multiCurrencyTrip_balancesAreInThbAndFollowingThePlanSettlesEveryone() {
        TestUser dad = register("Dad");
        TestUser mum = register("Mum");
        TestUser kid = register("Kid");
        TestGroup trip = createGroup("Europe", dad, mum, kid);
        dad.api().patch(trip.path(), body("autoConfirm", true)).expect(200);
        long grandma = addPlaceholder(dad, trip, "Grandma");
        List<Long> everyone = List.of(dad.id(), mum.id(), kid.id(), grandma);

        expense(dad, trip, "Dinner in Paris", "187.40", "EUR", "38.4567", dad.id(), everyone);
        expense(mum, trip, "Cable car Zermatt", "312.00", "CHF", "43.2109", mum.id(), everyone);
        expense(kid, trip, "Snacks Bangkok airport", "1000", "THB", null, kid.id(), List.of(kid.id(), dad.id(), mum.id()));
        expense(dad, trip, "Museum Vienna", "64.00", "EUR", "38.61", grandma, List.of(dad.id(), mum.id()));
        iou(mum, trip, mum, dad, "0.01");

        Map<Long, BigDecimal> balances = balances(dad, trip);
        assertThat(balances).hasSize(4);
        assertThat(balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");

        JsonNode plan = dad.api().get(trip.path() + "/settle-up").expect(200).get("transfers");
        assertThat(plan.size()).isBetween(1, 3); // at most n - 1 payments for 4 people
        for (JsonNode t : plan) {
            dad.api().post("/api/payments", body("groupId", trip.id(), "fromUserId", t.get("from").get("id").asLong(),
                    "toUserId", t.get("to").get("id").asLong(), "amount", money(t, "amount"))).expect(201);
        }
        assertThat(balances(dad, trip).values()).allSatisfy(v -> assertThat(v).isEqualTo(bd("0.00")));
        assertThat(dad.api().get(trip.path() + "/settle-up").expect(200).get("transfers")).isEmpty();
    }

    @Test
    void manyMembers_settleUpUsesAtMostNMinusOnePayments() {
        TestUser owner = register("Owner");
        TestGroup group = createGroup("Big family", owner);
        owner.api().patch(group.path(), body("autoConfirm", true)).expect(200);
        List<Long> ids = new ArrayList<>(List.of(owner.id()));
        for (int i = 1; i <= 14; i++) {
            ids.add(addPlaceholder(owner, group, "Relative " + i));
        }
        // Fifteen people, a dozen awkward amounts, a different payer each time.
        String[] amounts = {"100.00", "33.33", "1.01", "999.99", "250.50", "17.17", "0.07", "64.20",
                "5000.00", "12.34", "77.77", "408.15"};
        for (int i = 0; i < amounts.length; i++) {
            expense(owner, group, "Expense " + i, amounts[i], "THB", null, ids.get((i * 4) % ids.size()),
                    ids.subList(i % 5, ids.size()));
        }

        Map<Long, BigDecimal> balances = balances(owner, group);
        JsonNode plan = owner.api().get(group.path() + "/settle-up").expect(200).get("transfers");

        assertThat(balances).hasSize(15);
        assertThat(balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");
        assertThat(plan.size()).isLessThanOrEqualTo(14);
        Map<Long, BigDecimal> after = new HashMap<>(balances);
        for (JsonNode t : plan) {
            after.merge(t.get("from").get("id").asLong(), money(t, "amount"), BigDecimal::add);
            after.merge(t.get("to").get("id").asLong(), money(t, "amount").negate(), BigDecimal::add);
        }
        assertThat(after.values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
    }

    @Test
    void mySettleUp_combinesEveryGroupAndOneOnOneDebts() {
        TestUser me = register("Me");
        TestUser ben = register("Ben");
        TestUser cat = register("Cat");
        TestGroup trip = createGroup("Trip", me, ben, cat);
        TestGroup home = createGroup("Home", me, ben);
        // Trip (the README triangle): I owe Ben 100, Ben owes Cat 100, I owe Cat 50 -> I pay Cat 150.
        iou(me, trip, me, ben, "100");
        iou(ben, trip, ben, cat, "100");
        iou(me, trip, me, cat, "50");
        // Home: Ben owes me 40.
        iou(ben, home, ben, me, "40");
        // One-on-one: I owe Ben 25, Cat owes me 10 (pending: not counted), and I already paid Ben 5.
        me.api().post("/api/debts", body("debtorId", me.id(), "creditorId", ben.id(), "amount", "25",
                "description", "Lunch")).expect(201);
        me.api().post("/api/debts", body("debtorId", cat.id(), "creditorId", me.id(), "amount", "10",
                "description", "Coffee")).expect(201);
        ben.api().post("/api/payments", body("fromUserId", me.id(), "toUserId", ben.id(), "amount", "5")).expect(201);

        JsonNode mine = me.api().get("/api/me/settle-up").expect(200);

        assertThat(money(mine, "totalOwedByMe")).isEqualTo(bd("170.00"));   // 150 to Cat + 20 to Ben
        assertThat(money(mine, "totalOwedToMe")).isEqualTo(bd("40.00"));    // Ben, in Home
        assertThat(money(mine, "net")).isEqualTo(bd("-130.00"));
        assertThat(describe(mine.get("transfers"))).containsExactlyInAnyOrder(
                "Trip: Me -> Cat 150.00", "Home: Ben -> Me 40.00", "null: Me -> Ben 20.00");
        Map<String, BigDecimal> byPerson = new HashMap<>();
        mine.get("byPerson").forEach(p -> byPerson.put(p.get("user").get("displayName").asString(), money(p, "net")));
        assertThat(byPerson).containsOnly(Map.entry("Cat", bd("-150.00")), Map.entry("Ben", bd("20.00")));
        Map<String, BigDecimal> contexts = new HashMap<>();
        mine.get("contexts").forEach(c -> contexts.put(c.get("groupName").asString("direct"), money(c, "net")));
        assertThat(contexts).containsOnly(Map.entry("Trip", bd("-150.00")), Map.entry("Home", bd("40.00")),
                Map.entry("direct", bd("-20.00")));

        // Cat sees the mirror image of her side.
        JsonNode cats = cat.api().get("/api/me/settle-up").expect(200);
        assertThat(money(cats, "totalOwedToMe")).isEqualTo(bd("150.00"));
        assertThat(money(cats, "totalOwedByMe")).isEqualTo(bd("0.00"));
    }

    @Test
    void mySettleUpIsEmptyForSomeoneNew() {
        JsonNode mine = register("Newbie").api().get("/api/me/settle-up").expect(200);

        assertThat(money(mine, "net")).isEqualTo(bd("0.00"));
        assertThat(mine.get("transfers")).isEmpty();
        assertThat(mine.get("contexts")).isEmpty();
    }

    @Test
    void balancesAreForMembersOnly() {
        TestUser a = register("Ann");
        TestUser outsider = register("Olga");
        TestGroup group = createGroup("Trip", a);

        outsider.api().get(group.path() + "/balances").expect(404);
        outsider.api().get(group.path() + "/settle-up").expect(404);
    }

    // ---- helpers ----

    private long iou(TestUser by, TestGroup group, TestUser debtor, TestUser creditor, String amount) {
        return by.api().post(group.path() + "/debts", body("debtorId", debtor.id(), "creditorId", creditor.id(),
                "amount", amount, "description", "IOU")).expect(201).get("id").asLong();
    }

    private void expense(TestUser by, TestGroup group, String description, String amount, String currency,
            String rate, long payerId, List<Long> participants) {
        by.api().post(group.path() + "/expenses", body("description", description, "amount", amount,
                "currency", currency, "exchangeRate", rate, "payerId", payerId, "participantIds", participants))
                .expect(201);
    }

    private Map<Long, BigDecimal> balances(TestUser as, TestGroup group) {
        Map<Long, BigDecimal> map = new HashMap<>();
        as.api().get(group.path() + "/balances").expect(200).get("balances")
                .forEach(b -> map.put(b.get("user").get("id").asLong(), money(b, "net")));
        return map;
    }

    private static List<String> describe(JsonNode transfers) {
        List<String> lines = new ArrayList<>();
        transfers.forEach(t -> lines.add(t.get("groupName").asString("null") + ": "
                + t.get("from").get("displayName").asString() + " -> " + t.get("to").get("displayName").asString()
                + " " + money(t, "amount")));
        return lines;
    }
}
