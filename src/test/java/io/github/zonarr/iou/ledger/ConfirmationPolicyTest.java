package io.github.zonarr.iou.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConfirmationPolicyTest {

    private static final long ALICE = 1, BOB = 2;

    private final User alice = account(ALICE, "Alice");
    private final User mum = placeholder(9, "Mum");

    @Test
    void recordedByTheApproverThemselves_confirmed() {
        assertThat(ConfirmationPolicy.statusFor(ALICE, alice, group(false))).isEqualTo(ConfirmationStatus.CONFIRMED);
        assertThat(ConfirmationPolicy.statusFor(ALICE, alice, null)).isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    void recordedBySomebodyElse_pending() {
        assertThat(ConfirmationPolicy.statusFor(BOB, alice, group(false))).isEqualTo(ConfirmationStatus.PENDING);
        assertThat(ConfirmationPolicy.statusFor(BOB, alice, null)).isEqualTo(ConfirmationStatus.PENDING);
    }

    @Test
    void placeholderCannotRespond_confirmed() {
        assertThat(ConfirmationPolicy.statusFor(BOB, mum, group(false))).isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    void trustedGroup_confirmed_butOnlyInsideThatGroup() {
        assertThat(ConfirmationPolicy.statusFor(BOB, alice, group(true))).isEqualTo(ConfirmationStatus.CONFIRMED);
        assertThat(ConfirmationPolicy.statusFor(BOB, alice, null)).isEqualTo(ConfirmationStatus.PENDING);
    }

    private static Group group(boolean autoConfirm) {
        Group group = new Group("Trip", ALICE, "code");
        group.setAutoConfirm(autoConfirm);
        return group;
    }

    private static User account(long id, String name) {
        User user = User.account(name.toLowerCase() + "@example.com", "hash", name);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static User placeholder(long id, String name) {
        User user = User.placeholder(name, ALICE);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
