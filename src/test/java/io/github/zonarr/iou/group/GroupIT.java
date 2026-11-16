package io.github.zonarr.iou.group;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import io.github.zonarr.iou.support.TestUser;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class GroupIT extends IntegrationTest {

    @Test
    void creatorBecomesOwnerAndFirstMember() {
        TestUser alice = register("Alice");

        JsonNode group = alice.api().post("/api/groups", Map.of("name", "Europe trip")).expect(201);

        assertThat(group.get("name").asString()).isEqualTo("Europe trip");
        assertThat(group.get("ownerId").asLong()).isEqualTo(alice.id());
        assertThat(group.get("autoConfirm").asBoolean()).isFalse();
        assertThat(group.get("inviteCode").asString()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(names(group)).containsExactly("Alice");
        assertThat(alice.api().get("/api/groups").expect(200)).hasSize(1);
    }

    @Test
    void groupIsInvisibleToNonMembers() {
        TestUser alice = register("Alice");
        TestUser mallory = register("Mallory");
        long groupId = alice.api().post("/api/groups", Map.of("name", "Private")).expect(201).get("id").asLong();

        mallory.api().get("/api/groups/" + groupId).expect(404);
        mallory.api().post("/api/groups/" + groupId + "/members", Map.of("name", "Sneaky")).expect(404);
        assertThat(mallory.api().get("/api/groups").expect(200)).isEmpty();
    }

    @Test
    void addsPlaceholderAndRegisteredMembers() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        long groupId = alice.api().post("/api/groups", Map.of("name", "Trip")).expect(201).get("id").asLong();

        alice.api().post("/api/groups/" + groupId + "/members", Map.of("name", "Mum")).expect(201);
        JsonNode group = alice.api().post("/api/groups/" + groupId + "/members", Map.of("email", bob.email()))
                .expect(201);

        assertThat(names(group)).containsExactly("Alice", "Mum", "Bob");
        JsonNode mum = member(group, "Mum");
        assertThat(mum.get("placeholder").asBoolean()).isTrue();
        assertThat(member(group, "Bob").get("placeholder").asBoolean()).isFalse();
        assertThat(bob.api().get("/api/groups/" + groupId).expect(200).get("name").asString()).isEqualTo("Trip");
    }

    @Test
    void addMemberRejectsBadInput() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        String members = "/api/groups/" + alice.api().post("/api/groups", Map.of("name", "Trip")).expect(201)
                .get("id").asLong() + "/members";

        assertThat(alice.api().post(members, Map.of()).expect(400).get("error").asString())
                .isEqualTo("name_or_email");
        assertThat(alice.api().post(members, Map.of("name", "Mum", "email", bob.email())).expect(400)
                .get("error").asString()).isEqualTo("name_or_email");
        assertThat(alice.api().post(members, Map.of("email", "ghost@example.com")).expect(404)
                .get("error").asString()).isEqualTo("user_not_found");
        alice.api().post(members, Map.of("email", bob.email())).expect(201);
        assertThat(alice.api().post(members, Map.of("email", bob.email())).expect(409)
                .get("error").asString()).isEqualTo("already_member");
        alice.api().post(members, Map.of("name", "Mum")).expect(201);
        assertThat(alice.api().post(members, Map.of("name", "mum")).expect(409)
                .get("error").asString()).isEqualTo("name_taken");
    }

    @Test
    void onlyOwnerChangesSettingsAndResetsInvite() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        JsonNode created = alice.api().post("/api/groups", Map.of("name", "Trip")).expect(201);
        long groupId = created.get("id").asLong();
        String oldCode = created.get("inviteCode").asString();
        bob.api().post("/api/invites/" + oldCode + "/join").expect(200);

        assertThat(bob.api().patch("/api/groups/" + groupId, Map.of("autoConfirm", true)).expect(403)
                .get("error").asString()).isEqualTo("owner_only");
        bob.api().post("/api/groups/" + groupId + "/invite/reset").expect(403);

        JsonNode updated = alice.api().patch("/api/groups/" + groupId, Map.of("autoConfirm", true, "name", "Europe"))
                .expect(200);
        assertThat(updated.get("autoConfirm").asBoolean()).isTrue();
        assertThat(updated.get("name").asString()).isEqualTo("Europe");

        String newCode = alice.api().post("/api/groups/" + groupId + "/invite/reset").expect(200)
                .get("inviteCode").asString();
        assertThat(newCode).isNotEqualTo(oldCode);
        register("Carol").api().get("/api/invites/" + oldCode).expect(404);
    }

    @Test
    void inviteLinkShowsPlaceholdersAndJoinsAsNewMember() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        JsonNode created = alice.api().post("/api/groups", Map.of("name", "Trip")).expect(201);
        long groupId = created.get("id").asLong();
        String code = created.get("inviteCode").asString();
        alice.api().post("/api/groups/" + groupId + "/members", Map.of("name", "Mum")).expect(201);

        JsonNode preview = bob.api().get("/api/invites/" + code).expect(200);
        assertThat(preview.get("groupName").asString()).isEqualTo("Trip");
        assertThat(preview.get("alreadyMember").asBoolean()).isFalse();
        assertThat(preview.get("placeholders")).hasSize(1);
        assertThat(preview.get("placeholders").get(0).get("displayName").asString()).isEqualTo("Mum");

        JsonNode joined = bob.api().post("/api/invites/" + code + "/join").expect(200);
        bob.api().post("/api/invites/" + code + "/join").expect(200); // joining twice is harmless

        assertThat(names(joined)).containsExactly("Alice", "Mum", "Bob");
        assertThat(names(bob.api().get("/api/groups/" + groupId).expect(200))).hasSize(3);
        assertThat(bob.api().get("/api/invites/" + code).expect(200).get("alreadyMember").asBoolean()).isTrue();
        bob.api().get("/api/invites/nonsense").expect(404);
    }

    @Test
    void joiningThroughInviteCanClaimAPlaceholder() {
        TestUser alice = register("Alice");
        TestUser mum = register("Mother");
        JsonNode created = alice.api().post("/api/groups", Map.of("name", "Trip")).expect(201);
        long groupId = created.get("id").asLong();
        String code = created.get("inviteCode").asString();
        long placeholderId = member(
                alice.api().post("/api/groups/" + groupId + "/members", Map.of("name", "Mum")).expect(201), "Mum")
                .get("id").asLong();

        JsonNode joined = mum.api().post("/api/invites/" + code + "/join", Map.of("placeholderId", placeholderId))
                .expect(200);

        // The placeholder is gone; the real account sits in its place (keeping its position).
        assertThat(names(joined)).containsExactly("Alice", "Mother");
        assertThat(member(joined, "Mother").get("id").asLong()).isEqualTo(mum.id());
        assertThat(member(joined, "Mother").get("placeholder").asBoolean()).isFalse();
        // Claiming twice, or claiming something already claimed, fails cleanly.
        mum.api().post("/api/placeholders/" + placeholderId + "/claim", Map.of("inviteCode", code)).expect(404);
    }

    @Test
    void claimNeedsMembershipOrTheInviteCode() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser mallory = register("Mallory");
        JsonNode created = alice.api().post("/api/groups", Map.of("name", "Trip")).expect(201);
        long groupId = created.get("id").asLong();
        long placeholderId = member(
                alice.api().post("/api/groups/" + groupId + "/members", Map.of("name", "Robert")).expect(201),
                "Robert").get("id").asLong();

        mallory.api().post("/api/placeholders/" + placeholderId + "/claim").expect(404);
        mallory.api().post("/api/placeholders/" + placeholderId + "/claim", Map.of("inviteCode", "wrong")).expect(404);

        // Bob joined as a new member first, then realises "Robert" is him.
        bob.api().post("/api/invites/" + created.get("inviteCode").asString() + "/join").expect(200);
        JsonNode merged = bob.api().post("/api/placeholders/" + placeholderId + "/claim").expect(200);

        assertThat(names(merged)).containsExactly("Alice", "Bob");
    }

    @Test
    void renamesAndRemovesPlaceholders() {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        JsonNode created = alice.api().post("/api/groups", Map.of("name", "Trip")).expect(201);
        long groupId = created.get("id").asLong();
        bob.api().post("/api/invites/" + created.get("inviteCode").asString() + "/join").expect(200);
        long placeholderId = member(
                alice.api().post("/api/groups/" + groupId + "/members", Map.of("name", "Mun")).expect(201), "Mun")
                .get("id").asLong();
        String path = "/api/groups/" + groupId + "/members/";

        assertThat(names(alice.api().patch(path + placeholderId, Map.of("name", "Mum")).expect(200)))
                .containsExactly("Alice", "Bob", "Mum");
        alice.api().patch(path + bob.id(), Map.of("name", "Bobby")).expect(404); // real accounts name themselves

        assertThat(names(bob.api().delete(path + placeholderId).expect(200))).containsExactly("Alice", "Bob");
        alice.api().delete(path + alice.id()).expect(400);                       // owner cannot leave
        assertThat(names(bob.api().delete(path + bob.id()).expect(200))).containsExactly("Alice"); // Bob leaves
        bob.api().get("/api/groups/" + groupId).expect(404);
    }

    static List<String> names(JsonNode group) {
        return StreamSupport.stream(group.get("members").spliterator(), false)
                .map(m -> m.get("displayName").asString()).toList();
    }

    static JsonNode member(JsonNode group, String name) {
        return StreamSupport.stream(group.get("members").spliterator(), false)
                .filter(m -> m.get("displayName").asString().equals(name)).findFirst().orElseThrow();
    }
}
