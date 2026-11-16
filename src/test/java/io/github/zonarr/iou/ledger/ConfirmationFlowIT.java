package io.github.zonarr.iou.ledger;

import static io.github.zonarr.iou.ledger.DebtAndExpenseIT.assertError;
import static io.github.zonarr.iou.ledger.DebtAndExpenseIT.debtOf;
import static io.github.zonarr.iou.support.Json.bd;
import static io.github.zonarr.iou.support.Json.body;
import static io.github.zonarr.iou.support.Json.money;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.audit.AuditAction;
import io.github.zonarr.iou.audit.AuditEntity;
import io.github.zonarr.iou.audit.AuditLog;
import io.github.zonarr.iou.audit.AuditLogRepository;
import io.github.zonarr.iou.support.IntegrationTest;
import io.github.zonarr.iou.support.TestGroup;
import io.github.zonarr.iou.support.TestUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class ConfirmationFlowIT extends IntegrationTest {

    @Autowired
    AuditLogRepository auditLog;

    // ---- debts: accept / reject ----

    @Test
    void debtorAcceptsAPendingDebt() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = iou(bob, group, alice, bob, "200", "Taxi").get("id").asLong();

        assertError(bob.api().post("/api/debts/" + id + "/accept").expect(403), "debtor_only");
        JsonNode accepted = alice.api().post("/api/debts/" + id + "/accept").expect(200);

        assertThat(accepted.get("status").asString()).isEqualTo("CONFIRMED");
        assertError(alice.api().post("/api/debts/" + id + "/accept").expect(409), "not_pending");
        assertError(alice.api().post("/api/debts/" + id + "/reject").expect(409), "not_pending");
    }

    @Test
    void debtorRejectsAPendingDebt() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = iou(bob, group, alice, bob, "200", "Made up").get("id").asLong();

        JsonNode rejected = alice.api().post("/api/debts/" + id + "/reject").expect(200);

        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        // Still visible to the group, clearly marked.
        assertThat(bob.api().get("/api/debts/" + id).expect(200).get("status").asString()).isEqualTo("REJECTED");
        assertError(alice.api().post("/api/debts/" + id + "/accept").expect(409), "not_pending");
    }

    @Test
    void outsidersCannotAnswerOrSeeADebt() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser mallory = register("Mallory");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = iou(bob, group, alice, bob, "200", "Taxi").get("id").asLong();

        mallory.api().post("/api/debts/" + id + "/accept").expect(404);
        mallory.api().put("/api/debts/" + id, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 1, "description", "x")).expect(404);
        mallory.api().delete("/api/debts/" + id).expect(404);
    }

    // ---- debts: edit / delete ----

    @Test
    void editingTheAmountSendsAConfirmedDebtBackToPending() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = iou(bob, group, alice, bob, "200", "Taxi").get("id").asLong();
        alice.api().post("/api/debts/" + id + "/accept").expect(200);

        JsonNode edited = bob.api().put("/api/debts/" + id, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", "2000", "description", "Taxi")).expect(200);

        assertThat(edited.get("status").asString()).isEqualTo("PENDING");
        assertThat(money(edited, "thbAmount")).isEqualTo(bd("2000.00"));
    }

    @Test
    void changingOnlyTheRateAlsoCountsAsChangingTheAmount() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = bob.api().post(group.path() + "/debts", body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 10, "currency", "EUR", "exchangeRate", "38", "description", "Crepes")).expect(201)
                .get("id").asLong();
        alice.api().post("/api/debts/" + id + "/accept").expect(200);

        JsonNode edited = bob.api().put("/api/debts/" + id, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", 10, "currency", "EUR", "exchangeRate", "39", "description", "Crepes")).expect(200);

        assertThat(edited.get("status").asString()).isEqualTo("PENDING");
        assertThat(money(edited, "thbAmount")).isEqualTo(bd("390.00"));
    }

    @Test
    void editingOnlyTheDescriptionKeepsTheStatus() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = iou(bob, group, alice, bob, "200", "Taxy").get("id").asLong();
        alice.api().post("/api/debts/" + id + "/accept").expect(200);

        JsonNode edited = bob.api().put("/api/debts/" + id, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", "200.00", "description", "Taxi")).expect(200);

        assertThat(edited.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(edited.get("description").asString()).isEqualTo("Taxi");
    }

    @Test
    void debtorEditingTheirOwnDebtStaysConfirmed_andARejectedDebtCanBeCorrected() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long own = iou(alice, group, alice, bob, "200", "Taxi").get("id").asLong();
        long disputed = iou(bob, group, alice, bob, "999", "Too much").get("id").asLong();
        alice.api().post("/api/debts/" + disputed + "/reject").expect(200);

        JsonNode ownEdited = alice.api().put("/api/debts/" + own, body("debtorId", alice.id(),
                "creditorId", bob.id(), "amount", "250", "description", "Taxi")).expect(200);
        JsonNode corrected = bob.api().put("/api/debts/" + disputed, body("debtorId", alice.id(),
                "creditorId", bob.id(), "amount", "99", "description", "Fixed")).expect(200);

        assertThat(ownEdited.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(corrected.get("status").asString()).isEqualTo("PENDING"); // Alice gets to answer again
    }

    @Test
    void onlyTheRecorderOrTheGroupOwnerMayEditOrDelete() {
        TestUser owner = register("Olivia");
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", owner, alice, bob);
        long id = iou(bob, group, alice, bob, "200", "Taxi").get("id").asLong();
        Map<String, Object> change = body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", "1", "description", "Taxi");

        assertError(alice.api().put("/api/debts/" + id, change).expect(403), "not_allowed_to_edit");
        assertError(alice.api().delete("/api/debts/" + id).expect(403), "not_allowed_to_edit");

        // The owner fixes it; Alice (the debtor) still has to agree to the new amount.
        assertThat(owner.api().put("/api/debts/" + id, change).expect(200).get("status").asString())
                .isEqualTo("PENDING");
        owner.api().delete("/api/debts/" + id).expect(204);
        alice.api().get("/api/debts/" + id).expect(404);
        assertThat(alice.api().get(group.path() + "/debts").expect(200)).isEmpty();
    }

    @Test
    void everyChangeToADebtIsInTheAuditLog() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = iou(bob, group, alice, bob, "200", "Taxi").get("id").asLong();
        alice.api().post("/api/debts/" + id + "/accept").expect(200);
        bob.api().put("/api/debts/" + id, body("debtorId", alice.id(), "creditorId", bob.id(),
                "amount", "250", "description", "Taxi")).expect(200);
        alice.api().post("/api/debts/" + id + "/reject").expect(200);
        bob.api().delete("/api/debts/" + id).expect(204);

        List<AuditLog> history = auditLog.findByEntityTypeAndEntityIdOrderByIdAsc(AuditEntity.DEBT, id);

        assertThat(history).extracting(AuditLog::getAction).containsExactly(AuditAction.CREATED,
                AuditAction.ACCEPTED, AuditAction.UPDATED, AuditAction.REJECTED, AuditAction.DELETED);
        assertThat(history).extracting(AuditLog::getActorId)
                .containsExactly(bob.id(), alice.id(), bob.id(), alice.id(), bob.id());
        assertThat(history).allSatisfy(h -> {
            assertThat(h.getGroupId()).isEqualTo(group.id());
            assertThat(h.getInvolvedUserIds()).containsExactlyInAnyOrder(alice.id(), bob.id());
        });
        AuditLog created = history.get(0);
        assertThat(created.getBeforeState()).isNull();
        assertThat(created.getAfterState()).contains("\"thbAmount\": \"200.00\"").contains("\"status\": \"PENDING\"");
        AuditLog updated = history.get(2);
        assertThat(updated.getBeforeState()).contains("\"thbAmount\": \"200.00\"").contains("\"status\": \"CONFIRMED\"");
        assertThat(updated.getAfterState()).contains("\"thbAmount\": \"250.00\"").contains("\"status\": \"PENDING\"");
        AuditLog deleted = history.get(4);
        assertThat(deleted.getBeforeState()).contains("\"status\": \"REJECTED\"");
        assertThat(deleted.getAfterState()).isNull();
    }

    // ---- expenses: edit / delete ----

    @Test
    void editingAnExpenseAmountSendsItsDebtsBackToPending() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup group = createGroup("Trip", alice, bob, carol);
        JsonNode expense = dinner(alice, group, "900", alice, alice, bob, carol);
        long id = expense.get("id").asLong();
        bob.api().post("/api/debts/" + debtOf(expense, bob).get("id").asLong() + "/accept").expect(200);
        carol.api().post("/api/debts/" + debtOf(expense, carol).get("id").asLong() + "/accept").expect(200);

        JsonNode edited = alice.api().put("/api/expenses/" + id, body("description", "Dinner", "amount", "1200",
                "payerId", alice.id(), "participantIds", List.of(alice.id(), bob.id(), carol.id()))).expect(200);

        assertThat(money(edited, "thbAmount")).isEqualTo(bd("1200.00"));
        assertThat(edited.get("debts")).hasSize(2);
        for (JsonNode debt : edited.get("debts")) {
            assertThat(money(debt, "thbAmount")).isEqualTo(bd("400.00"));
            assertThat(debt.get("status").asString()).isEqualTo("PENDING");
        }
        // Same debt rows were updated, not replaced.
        assertThat(debtOf(edited, bob).get("id").asLong()).isEqualTo(debtOf(expense, bob).get("id").asLong());
    }

    @Test
    void editingAnExpense_unchangedDebtsKeepTheirStatus_removedPeopleLoseTheirDebt() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestUser dave = register("Dave");
        TestGroup group = createGroup("Trip", alice, bob, carol, dave);
        // 900 between Alice, Bob and Carol: 300 each.
        JsonNode expense = dinner(alice, group, "900", alice, alice, bob, carol);
        long id = expense.get("id").asLong();
        bob.api().post("/api/debts/" + debtOf(expense, bob).get("id").asLong() + "/accept").expect(200);

        // Now 900 between Alice, Bob and Dave: Bob still owes 300 (unchanged), Carol drops out, Dave is new.
        JsonNode edited = alice.api().put("/api/expenses/" + id, body("description", "Dinner (fixed)",
                "amount", "900", "payerId", alice.id(),
                "participantIds", List.of(alice.id(), bob.id(), dave.id()))).expect(200);

        assertThat(edited.get("debts")).hasSize(2);
        assertThat(debtOf(edited, bob).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(debtOf(edited, bob).get("description").asString()).isEqualTo("Dinner (fixed)");
        assertThat(debtOf(edited, dave).get("status").asString()).isEqualTo("PENDING");
        assertThat(alice.api().get(group.path() + "/debts").expect(200)).hasSize(2); // Carol's debt is gone
        assertThat(edited.get("shares")).hasSize(3);
    }

    @Test
    void debtsFromAnExpenseAreChangedThroughTheExpenseOnly() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        JsonNode expense = dinner(alice, group, "100", alice, alice, bob);
        long debtId = debtOf(expense, bob).get("id").asLong();

        assertError(alice.api().put("/api/debts/" + debtId, body("debtorId", bob.id(), "creditorId", alice.id(),
                "amount", 1, "description", "x")).expect(409), "part_of_expense");
        assertError(alice.api().delete("/api/debts/" + debtId).expect(409), "part_of_expense");
        assertError(bob.api().put("/api/expenses/" + expense.get("id").asLong(), body("description", "x",
                "amount", 1, "payerId", alice.id(), "participantIds", List.of(bob.id()))).expect(403),
                "not_allowed_to_edit");
    }

    @Test
    void deletingAnExpenseRemovesItsDebtsAndIsAudited() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = dinner(alice, group, "100", alice, alice, bob).get("id").asLong();

        alice.api().delete("/api/expenses/" + id).expect(204);

        assertThat(alice.api().get(group.path() + "/expenses").expect(200)).isEmpty();
        assertThat(alice.api().get(group.path() + "/debts").expect(200)).isEmpty();
        alice.api().get("/api/expenses/" + id).expect(404);
        List<AuditLog> history = auditLog.findByEntityTypeAndEntityIdOrderByIdAsc(AuditEntity.EXPENSE, id);
        assertThat(history).extracting(AuditLog::getAction).containsExactly(AuditAction.CREATED, AuditAction.DELETED);
        assertThat(history.get(0).getAfterState()).contains("\"debts\"").contains("\"shares\"").contains("\"payers\"");
        assertThat(history.get(1).getBeforeState()).contains("\"thbAmount\": \"100.00\"");
    }

    // ---- payments ----

    @Test
    void paymentIsPendingUntilTheReceiverConfirms() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);

        JsonNode payment = alice.api().post("/api/payments", body("groupId", group.id(),
                "fromUserId", alice.id(), "toUserId", bob.id(), "amount", "200", "note", "Cash")).expect(201);
        long id = payment.get("id").asLong();

        assertThat(payment.get("status").asString()).isEqualTo("PENDING");
        assertThat(payment.get("from").get("displayName").asString()).isEqualTo("Alice");
        assertThat(payment.get("to").get("displayName").asString()).isEqualTo("Bob");
        assertError(alice.api().post("/api/payments/" + id + "/confirm").expect(403), "receiver_only");
        assertThat(bob.api().post("/api/payments/" + id + "/confirm").expect(200).get("status").asString())
                .isEqualTo("CONFIRMED");
        assertError(bob.api().post("/api/payments/" + id + "/reject").expect(409), "not_pending");
        assertThat(alice.api().get(group.path() + "/payments").expect(200)).hasSize(1);
    }

    @Test
    void receiverCanRejectAPayment() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long id = alice.api().post("/api/payments", body("groupId", group.id(), "fromUserId", alice.id(),
                "toUserId", bob.id(), "amount", "200")).expect(201).get("id").asLong();

        assertThat(bob.api().post("/api/payments/" + id + "/reject").expect(200).get("status").asString())
                .isEqualTo("REJECTED");
    }

    @Test
    void paymentIsConfirmedAtOnceWhenTheReceiverRecordsIt_orIsAPlaceholder_orTheGroupIsTrusted() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long mum = addPlaceholder(alice, group, "Mum");

        JsonNode byReceiver = bob.api().post("/api/payments", body("groupId", group.id(),
                "fromUserId", alice.id(), "toUserId", bob.id(), "amount", "50")).expect(201);
        JsonNode toPlaceholder = alice.api().post("/api/payments", body("groupId", group.id(),
                "fromUserId", alice.id(), "toUserId", mum, "amount", "50")).expect(201);
        JsonNode fromPlaceholder = alice.api().post("/api/payments", body("groupId", group.id(),
                "fromUserId", mum, "toUserId", bob.id(), "amount", "50")).expect(201);
        alice.api().patch(group.path(), body("autoConfirm", true)).expect(200);
        JsonNode trusted = alice.api().post("/api/payments", body("groupId", group.id(),
                "fromUserId", alice.id(), "toUserId", bob.id(), "amount", "50")).expect(201);

        assertThat(byReceiver.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(toPlaceholder.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(fromPlaceholder.get("status").asString()).isEqualTo("PENDING"); // Bob is real and must confirm
        assertThat(trusted.get("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void foreignCurrencyPaymentAndEditBackToPending() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        JsonNode payment = alice.api().post("/api/payments", body("groupId", group.id(), "fromUserId", alice.id(),
                "toUserId", bob.id(), "amount", "50", "currency", "EUR", "exchangeRate", "38.5")).expect(201);
        long id = payment.get("id").asLong();
        bob.api().post("/api/payments/" + id + "/confirm").expect(200);

        assertThat(money(payment, "thbAmount")).isEqualTo(bd("1925.00"));
        assertThat(payment.get("currency").asString()).isEqualTo("EUR");
        assertThat(bob.api().get(group.path() + "/rates").expect(200).get("EUR").decimalValue())
                .isEqualByComparingTo("38.5");

        JsonNode noteOnly = alice.api().put("/api/payments/" + id, body("fromUserId", alice.id(),
                "toUserId", bob.id(), "amount", "50", "currency", "EUR", "exchangeRate", "38.5", "note", "cash"))
                .expect(200);
        JsonNode amountChanged = alice.api().put("/api/payments/" + id, body("fromUserId", alice.id(),
                "toUserId", bob.id(), "amount", "60", "currency", "EUR", "exchangeRate", "38.5", "note", "cash"))
                .expect(200);

        assertThat(noteOnly.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(amountChanged.get("status").asString()).isEqualTo("PENDING");
        assertError(bob.api().put("/api/payments/" + id, body("fromUserId", alice.id(), "toUserId", bob.id(),
                "amount", "1")).expect(403), "not_allowed_to_edit");
        alice.api().delete("/api/payments/" + id).expect(204);
        assertThat(alice.api().get(group.path() + "/payments").expect(200)).isEmpty();
        assertThat(auditLog.findByEntityTypeAndEntityIdOrderByIdAsc(AuditEntity.PAYMENT, id))
                .extracting(AuditLog::getAction).containsExactly(AuditAction.CREATED, AuditAction.CONFIRMED,
                        AuditAction.UPDATED, AuditAction.UPDATED, AuditAction.DELETED);
    }

    @Test
    void oneOnOnePaymentOutsideGroups() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");

        long id = alice.api().post("/api/payments", body("fromUserId", alice.id(), "toUserId", bob.id(),
                "amount", "75")).expect(201).get("id").asLong();

        assertThat(bob.api().get("/api/payments").expect(200)).hasSize(1);
        assertThat(carol.api().get("/api/payments").expect(200)).isEmpty();
        carol.api().get("/api/payments/" + id).expect(404);
        carol.api().post("/api/payments/" + id + "/confirm").expect(404);
        assertError(carol.api().post("/api/payments", body("fromUserId", alice.id(), "toUserId", bob.id(),
                "amount", "75")).expect(400), "must_involve_you");
        assertError(alice.api().post("/api/payments", body("fromUserId", alice.id(), "toUserId", alice.id(),
                "amount", "75")).expect(400), "same_person");
        bob.api().post("/api/payments/" + id + "/confirm").expect(200);
    }

    // ---- placeholders and claiming ----

    @Test
    void afterClaimingHistoryMovesOverAndTheNormalFlowApplies() {
        TestUser alice = register("Alice");
        TestUser mum = register("Mother");
        TestGroup group = createGroup("Trip", alice);
        long placeholder = addPlaceholder(alice, group, "Mum");
        JsonNode before = dinner(alice, group, "600", alice, alice, placeholder);
        assertThat(before.get("debts").get(0).get("status").asString()).isEqualTo("CONFIRMED");

        mum.api().post("/api/invites/" + group.inviteCode() + "/join", body("placeholderId", placeholder)).expect(200);

        // The old debt now belongs to the real account and is still confirmed.
        JsonNode moved = mum.api().get("/api/expenses/" + before.get("id").asLong()).expect(200);
        assertThat(moved.get("debts").get(0).get("debtor").get("id").asLong()).isEqualTo(mum.id());
        assertThat(moved.get("debts").get(0).get("debtor").get("displayName").asString()).isEqualTo("Mother");
        assertThat(moved.get("debts").get(0).get("status").asString()).isEqualTo("CONFIRMED");
        // New debts recorded by somebody else now wait for her answer.
        JsonNode after = dinner(alice, group, "400", alice, alice, mum.id());
        assertThat(after.get("debts").get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(mum.api().get("/api/me/pending").expect(200).get("debts")).hasSize(1);
    }

    @Test
    void claimIsRefusedWhenItWouldCreateADebtToYourself() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long placeholder = addPlaceholder(alice, group, "Robert");
        dinner(alice, group, "90", bob.id(), bob.id(), placeholder); // Robert owes Bob

        assertError(bob.api().post("/api/placeholders/" + placeholder + "/claim").expect(409), "claim_conflict");
    }

    // ---- inbox ----

    @Test
    void pendingInboxListsWhatNeedsMyAnswer_andAcceptAllClearsIt() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup trip = createGroup("Trip", alice, bob);
        TestGroup home = createGroup("Home", alice, bob);
        dinner(alice, trip, "100", alice, alice, bob);
        dinner(alice, trip, "50", alice, alice, bob);
        dinner(alice, home, "30", alice, alice, bob);
        alice.api().post("/api/payments", body("groupId", trip.id(), "fromUserId", alice.id(),
                "toUserId", bob.id(), "amount", "20")).expect(201);

        JsonNode inbox = bob.api().get("/api/me/pending").expect(200);
        assertThat(inbox.get("debts")).hasSize(3);
        assertThat(inbox.get("payments")).hasSize(1);
        assertThat(alice.api().get("/api/me/pending").expect(200).get("debts")).isEmpty();

        JsonNode result = bob.api().post("/api/me/pending/accept-all", body("groupId", trip.id())).expect(200);
        assertThat(result.get("debtsAccepted").asInt()).isEqualTo(2);
        assertThat(result.get("paymentsConfirmed").asInt()).isEqualTo(1);
        assertThat(bob.api().get("/api/me/pending").expect(200).get("debts")).hasSize(1); // the other group's

        bob.api().post("/api/me/pending/accept-all").expect(200);
        assertThat(bob.api().get("/api/me/pending").expect(200).get("debts")).isEmpty();
    }

    @Test
    void groupAndMemberEventsAreAudited() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup("Trip", alice, bob);
        long mum = addPlaceholder(alice, group, "Mum");
        alice.api().patch(group.path(), body("autoConfirm", true)).expect(200);
        bob.api().post("/api/placeholders/" + mum + "/claim").expect(200);

        assertThat(auditLog.findForGroup(group.id(), null, org.springframework.data.domain.PageRequest.of(0, 50)))
                .extracting(a -> a.getEntityType() + " " + a.getAction())
                .containsExactly("MEMBER CLAIMED", "GROUP UPDATED", "MEMBER ADDED", "MEMBER JOINED", "GROUP CREATED");
    }

    // ---- helpers ----

    private JsonNode iou(TestUser by, TestGroup group, TestUser debtor, TestUser creditor, String amount,
            String description) {
        return by.api().post(group.path() + "/debts", body("debtorId", debtor.id(), "creditorId", creditor.id(),
                "amount", amount, "description", description)).expect(201);
    }

    private JsonNode dinner(TestUser by, TestGroup group, String amount, TestUser payer, TestUser... participants) {
        return by.api().post(group.path() + "/expenses", body("description", "Dinner", "amount", amount,
                "payerId", payer.id(),
                "participantIds", java.util.Arrays.stream(participants).map(TestUser::id).toList())).expect(201);
    }

    private JsonNode dinner(TestUser by, TestGroup group, String amount, Object payerId, Object... participantIds) {
        Object payer = payerId instanceof TestUser u ? u.id() : payerId;
        List<Object> ids = java.util.Arrays.stream(participantIds)
                .map(p -> p instanceof TestUser u ? (Object) u.id() : p).toList();
        return by.api().post(group.path() + "/expenses", body("description", "Dinner", "amount", amount,
                "payerId", payer, "participantIds", ids)).expect(201);
    }
}
