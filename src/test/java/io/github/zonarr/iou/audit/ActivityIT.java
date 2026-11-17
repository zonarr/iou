package io.github.zonarr.iou.audit;

import static io.github.zonarr.iou.support.Json.body;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import io.github.zonarr.iou.support.TestGroup;
import io.github.zonarr.iou.support.TestUser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ActivityIT extends IntegrationTest {

    @Test
    void groupTimelineTellsTheStoryNewestFirst() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long expenseId = alice.api().post(group.path() + "/expenses", body("description", "Dinner",
                "amount", "100", "currency", "EUR", "exchangeRate", "38.5", "payerId", alice.id(),
                "participantIds", List.of(alice.id(), bob.id()))).expect(201).get("id").asLong();
        long debtId = alice.api().get("/api/expenses/" + expenseId).expect(200).get("debts").get(0).get("id").asLong();
        bob.api().post("/api/debts/" + debtId + "/accept").expect(200);
        alice.api().put("/api/expenses/" + expenseId, body("description", "Dinner", "amount", "120",
                "currency", "EUR", "exchangeRate", "38.5", "payerId", alice.id(),
                "participantIds", List.of(alice.id(), bob.id()))).expect(200);
        long paymentId = bob.api().post("/api/payments", body("groupId", group.id(), "fromUserId", bob.id(),
                "toUserId", alice.id(), "amount", "1000")).expect(201).get("id").asLong();
        alice.api().post("/api/payments/" + paymentId + "/confirm").expect(200);

        JsonNode page = bob.api().get(group.path() + "/activity").expect(200);

        assertThat(describe(page)).containsExactly(
                "Alice PAYMENT CONFIRMED", "Bob PAYMENT CREATED", "Alice EXPENSE UPDATED", "Bob DEBT ACCEPTED",
                "Alice EXPENSE CREATED", "Bob MEMBER JOINED", "Alice GROUP CREATED");
        assertThat(page.get("nextBefore").isNull()).isTrue();

        JsonNode updated = page.get("entries").get(2);
        assertThat(updated.get("groupName").asString()).isEqualTo("Trip");
        assertThat(updated.get("before").get("thbAmount").asString()).isEqualTo("3850.00");
        assertThat(updated.get("after").get("thbAmount").asString()).isEqualTo("4620.00");
        assertThat(updated.get("after").get("originalAmount").asString()).isEqualTo("120.00");
        assertThat(updated.get("after").get("currency").asString()).isEqualTo("EUR");
        assertThat(updated.get("before").get("debts").get(0).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(updated.get("after").get("debts").get(0).get("status").asString()).isEqualTo("PENDING");

        // Ids inside the snapshots can be turned into names.
        JsonNode payment = page.get("entries").get(1);
        assertThat(page.get("people").get(String.valueOf(payment.get("after").get("fromUserId").asLong())).asString())
                .isEqualTo("Bob");
        assertThat(page.get("people").get(String.valueOf(payment.get("after").get("toUserId").asLong())).asString())
                .isEqualTo("Alice");
        assertThat(page.get("entries").get(0).get("at").asString()).isNotBlank();
    }

    @Test
    void timelineIsPaged() {
        TestUser alice = register("Alice");
        TestGroup group = createGroup("Trip", alice);
        for (int i = 1; i <= 5; i++) {
            addPlaceholder(alice, group, "Person " + i);
        }

        JsonNode first = alice.api().get(group.path() + "/activity?limit=4").expect(200);
        JsonNode second = alice.api().get(group.path() + "/activity?limit=4&before="
                + first.get("nextBefore").asLong()).expect(200);

        assertThat(first.get("entries")).hasSize(4);
        assertThat(second.get("entries")).hasSize(2); // 5 additions + the group's creation = 6
        assertThat(second.get("nextBefore").isNull()).isTrue();
        assertThat(first.get("entries").get(0).get("after").get("name").asString()).isEqualTo("Person 5");
        assertThat(second.get("entries").get(1).get("action").asString()).isEqualTo("CREATED");
    }

    @Test
    void oldEntriesAboutAClaimedPlaceholderShowTheRealName() {
        TestUser alice = register("Alice");
        TestUser mum = register("Mother");
        TestGroup group = createGroup("Trip", alice);
        long placeholder = addPlaceholder(alice, group, "Mum");
        alice.api().post(group.path() + "/debts", body("debtorId", placeholder, "creditorId", alice.id(),
                "amount", "50", "description", "Tickets")).expect(201);
        mum.api().post("/api/invites/" + group.inviteCode() + "/join", body("placeholderId", placeholder)).expect(200);

        JsonNode page = mum.api().get(group.path() + "/activity").expect(200);

        assertThat(describe(page).get(0)).isEqualTo("Mother MEMBER CLAIMED");
        JsonNode debtCreated = page.get("entries").get(1);
        long debtorId = debtCreated.get("after").get("debtorId").asLong();
        assertThat(debtorId).isEqualTo(placeholder); // the snapshot is history and is not rewritten
        assertThat(page.get("people").get(String.valueOf(debtorId)).asString()).isEqualTo("Mother");
    }

    @Test
    void myTimelineCoversMyGroupsAndMyOneOnOneEntriesOnly() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        createGroup("Alice and Bob", alice, bob);
        TestGroup private2 = createGroup("Bob and Carol", bob, carol);
        alice.api().post("/api/debts", body("debtorId", alice.id(), "creditorId", bob.id(), "amount", "25",
                "description", "Lunch")).expect(201);
        bob.api().post("/api/debts", body("debtorId", bob.id(), "creditorId", carol.id(), "amount", "9",
                "description", "Secret")).expect(201);

        List<String> alices = describe(alice.api().get("/api/me/activity").expect(200));
        List<String> carols = describe(carol.api().get("/api/me/activity").expect(200));

        assertThat(alices).containsExactly("Alice DEBT CREATED", "Bob MEMBER JOINED", "Alice GROUP CREATED");
        assertThat(carols).containsExactly("Bob DEBT CREATED", "Carol MEMBER JOINED", "Bob GROUP CREATED");
        alice.api().get(private2.path() + "/activity").expect(404);
    }

    private static List<String> describe(JsonNode page) {
        List<String> lines = new ArrayList<>();
        page.get("entries").forEach(e -> lines.add(e.get("actor").get("displayName").asString() + " "
                + e.get("entityType").asString() + " " + e.get("action").asString()));
        return lines;
    }
}
