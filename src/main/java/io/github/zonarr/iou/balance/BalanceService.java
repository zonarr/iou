package io.github.zonarr.iou.balance;

import io.github.zonarr.iou.balance.BalanceDtos.ContextBalance;
import io.github.zonarr.iou.balance.BalanceDtos.GroupBalances;
import io.github.zonarr.iou.balance.BalanceDtos.GroupSettleUp;
import io.github.zonarr.iou.balance.BalanceDtos.MemberBalance;
import io.github.zonarr.iou.balance.BalanceDtos.MySettleUp;
import io.github.zonarr.iou.balance.BalanceDtos.MyTransfer;
import io.github.zonarr.iou.balance.BalanceDtos.PersonNet;
import io.github.zonarr.iou.balance.BalanceDtos.Transfer;
import io.github.zonarr.iou.group.Group;
import io.github.zonarr.iou.group.GroupMember;
import io.github.zonarr.iou.group.GroupMemberRepository;
import io.github.zonarr.iou.group.GroupRepository;
import io.github.zonarr.iou.group.GroupService;
import io.github.zonarr.iou.ledger.ConfirmationStatus;
import io.github.zonarr.iou.ledger.Debt;
import io.github.zonarr.iou.ledger.DebtRepository;
import io.github.zonarr.iou.ledger.Payment;
import io.github.zonarr.iou.ledger.PaymentRepository;
import io.github.zonarr.iou.money.Money;
import io.github.zonarr.iou.money.SettleUp;
import io.github.zonarr.iou.user.UserDirectory;
import io.github.zonarr.iou.user.UserSummary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Balances and settle-up suggestions. Read-only: nothing here ever changes a debt or payment.
 *
 * <p>A person's net balance is what others owe them minus what they owe, counting only
 * <em>confirmed</em> debts and payments, always in THB:
 * <pre>
 *   confirmed debt     debtor -> creditor:  creditor +amount, debtor -amount
 *   confirmed payment  from   -> to:        from     +amount, to     -amount
 * </pre>
 */
@Service
public class BalanceService {

    private final GroupService groupService;
    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final DebtRepository debts;
    private final PaymentRepository payments;
    private final UserDirectory directory;

    public BalanceService(GroupService groupService, GroupRepository groups, GroupMemberRepository members,
            DebtRepository debts, PaymentRepository payments, UserDirectory directory) {
        this.groupService = groupService;
        this.groups = groups;
        this.members = members;
        this.debts = debts;
        this.payments = payments;
        this.directory = directory;
    }

    @Transactional(readOnly = true)
    public GroupBalances groupBalances(long actorId, long groupId) {
        groupService.requireMember(groupId, actorId);
        Map<Long, BigDecimal> nets = groupNets(groupId);
        Map<Long, UserSummary> people = directory.summaries(nets.keySet());
        List<MemberBalance> balances = nets.entrySet().stream()
                .map(e -> new MemberBalance(people.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing((MemberBalance b) -> b.user().displayName().toLowerCase())
                        .thenComparing(b -> b.user().id()))
                .toList();
        int pendingDebts = (int) debts.findActiveInGroup(groupId).stream()
                .filter(d -> d.getStatus() == ConfirmationStatus.PENDING).count();
        int pendingPayments = (int) payments.findActiveInGroup(groupId).stream()
                .filter(p -> p.getStatus() == ConfirmationStatus.PENDING).count();
        return new GroupBalances(groupId, Money.BASE_CURRENCY, balances, pendingDebts, pendingPayments);
    }

    @Transactional(readOnly = true)
    public GroupSettleUp groupSettleUp(long actorId, long groupId) {
        groupService.requireMember(groupId, actorId);
        List<SettleUp.Transfer> plan = SettleUp.plan(groupNets(groupId));
        Set<Long> ids = new HashSet<>();
        plan.forEach(t -> {
            ids.add(t.fromId());
            ids.add(t.toId());
        });
        Map<Long, UserSummary> people = directory.summaries(ids);
        return new GroupSettleUp(groupId, Money.BASE_CURRENCY, plan.stream()
                .map(t -> new Transfer(people.get(t.fromId()), people.get(t.toId()), t.amount())).toList());
    }

    /**
     * My position everywhere. For each group, the group's own settle-up plan is worked out and
     * the payments that involve me are kept; debts outside groups are netted person by person.
     * If everybody follows their group's plan, these are exactly the payments I will make and
     * receive, so the two views never contradict each other.
     */
    @Transactional(readOnly = true)
    public MySettleUp mySettleUp(long actorId) {
        List<ContextBalance> contexts = new ArrayList<>();
        List<SettleUp.Transfer> mine = new ArrayList<>();
        List<Long> transferGroup = new ArrayList<>();
        Map<Long, String> groupNames = new LinkedHashMap<>();

        for (Group group : groups.findAllForUser(actorId)) {
            groupNames.put(group.getId(), group.getName());
            Map<Long, BigDecimal> nets = groupNets(group.getId());
            contexts.add(new ContextBalance(group.getId(), group.getName(), nets.getOrDefault(actorId, Money.ZERO)));
            for (SettleUp.Transfer t : SettleUp.plan(nets)) {
                if (t.fromId() == actorId || t.toId() == actorId) {
                    mine.add(t);
                    transferGroup.add(group.getId());
                }
            }
        }

        // Outside groups: what each person and I owe each other directly.
        Map<Long, BigDecimal> direct = new TreeMap<>();
        for (Debt d : debts.findConfirmedDirectFor(actorId)) {
            if (d.getCreditorId().equals(actorId)) {
                direct.merge(d.getDebtorId(), d.getMoney().thbAmount(), BigDecimal::add);
            } else {
                direct.merge(d.getCreditorId(), d.getMoney().thbAmount().negate(), BigDecimal::add);
            }
        }
        for (Payment p : payments.findConfirmedDirectFor(actorId)) {
            if (p.getFromUserId().equals(actorId)) {
                direct.merge(p.getToUserId(), p.getMoney().thbAmount(), BigDecimal::add);
            } else {
                direct.merge(p.getFromUserId(), p.getMoney().thbAmount().negate(), BigDecimal::add);
            }
        }
        BigDecimal directNet = Money.ZERO;
        for (Map.Entry<Long, BigDecimal> e : direct.entrySet()) {
            directNet = directNet.add(e.getValue());
            if (e.getValue().signum() > 0) {
                mine.add(new SettleUp.Transfer(e.getKey(), actorId, e.getValue()));
                transferGroup.add(null);
            } else if (e.getValue().signum() < 0) {
                mine.add(new SettleUp.Transfer(actorId, e.getKey(), e.getValue().negate()));
                transferGroup.add(null);
            }
        }
        if (!direct.isEmpty()) {
            contexts.add(new ContextBalance(null, null, directNet));
        }

        Set<Long> ids = new HashSet<>();
        mine.forEach(t -> {
            ids.add(t.fromId());
            ids.add(t.toId());
        });
        Map<Long, UserSummary> people = directory.summaries(ids);

        BigDecimal owedByMe = Money.ZERO;
        BigDecimal owedToMe = Money.ZERO;
        Map<Long, BigDecimal> perPerson = new TreeMap<>();
        List<MyTransfer> transfers = new ArrayList<>();
        for (int i = 0; i < mine.size(); i++) {
            SettleUp.Transfer t = mine.get(i);
            Long groupId = transferGroup.get(i);
            transfers.add(new MyTransfer(groupId, groupId == null ? null : groupNames.get(groupId),
                    people.get(t.fromId()), people.get(t.toId()), t.amount()));
            if (t.fromId() == actorId) {
                owedByMe = owedByMe.add(t.amount());
                perPerson.merge(t.toId(), t.amount().negate(), BigDecimal::add);
            } else {
                owedToMe = owedToMe.add(t.amount());
                perPerson.merge(t.fromId(), t.amount(), BigDecimal::add);
            }
        }
        List<PersonNet> byPerson = perPerson.entrySet().stream()
                .filter(e -> e.getValue().signum() != 0)
                .map(e -> new PersonNet(people.get(e.getKey()), e.getValue()))
                .toList();
        return new MySettleUp(Money.BASE_CURRENCY, owedByMe, owedToMe, owedToMe.subtract(owedByMe), contexts,
                transfers, byPerson);
    }

    /** Net balance of every member of a group (members with no activity are included at 0.00). */
    private Map<Long, BigDecimal> groupNets(long groupId) {
        Map<Long, BigDecimal> nets = new TreeMap<>();
        for (GroupMember member : members.findByGroupIdOrderByJoinedAtAscIdAsc(groupId)) {
            nets.put(member.getUserId(), Money.ZERO);
        }
        for (Debt d : debts.findConfirmedInGroup(groupId)) {
            nets.merge(d.getCreditorId(), d.getMoney().thbAmount(), BigDecimal::add);
            nets.merge(d.getDebtorId(), d.getMoney().thbAmount().negate(), BigDecimal::add);
        }
        for (Payment p : payments.findConfirmedInGroup(groupId)) {
            nets.merge(p.getFromUserId(), p.getMoney().thbAmount(), BigDecimal::add);
            nets.merge(p.getToUserId(), p.getMoney().thbAmount().negate(), BigDecimal::add);
        }
        return nets;
    }
}
