package io.github.zonarr.iou.ledger;

import io.github.zonarr.iou.money.Money;
import io.github.zonarr.iou.money.MoneyAmount;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The before/after pictures stored in the audit log. People are stored as ids (names are
 * looked up when the history is shown) and money as plain strings like "33.33".
 */
final class Snapshots {

    private Snapshots() {
    }

    static Map<String, Object> of(Debt d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("groupId", d.getGroupId());
        map.put("expenseId", d.getExpenseId());
        map.put("debtorId", d.getDebtorId());
        map.put("creditorId", d.getCreditorId());
        putMoney(map, d.getMoney());
        map.put("description", d.getDescription());
        map.put("status", d.getStatus().name());
        return map;
    }

    static Map<String, Object> of(Payment p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("groupId", p.getGroupId());
        map.put("fromUserId", p.getFromUserId());
        map.put("toUserId", p.getToUserId());
        putMoney(map, p.getMoney());
        map.put("note", p.getNote());
        map.put("status", p.getStatus().name());
        return map;
    }

    static Map<String, Object> of(Expense e, List<ExpensePayer> payers, List<ExpenseShare> shares, List<Debt> debts) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("groupId", e.getGroupId());
        map.put("description", e.getDescription());
        putMoney(map, e.getMoney());
        map.put("splitType", e.getSplitType().name());
        map.put("spentOn", e.getSpentOn().toString());
        map.put("payers", payers.stream().map(p -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("userId", p.getUserId());
            line.put("originalAmount", p.getOriginalAmount().toPlainString());
            line.put("thbAmount", p.getThbAmount().toPlainString());
            return line;
        }).toList());
        map.put("shares", shares.stream().map(s -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("userId", s.getUserId());
            line.put("originalAmount", s.getOriginalAmount().toPlainString());
            line.put("thbAmount", s.getThbAmount().toPlainString());
            line.put("weight", s.getWeight() == null ? null : Money.displayRate(s.getWeight()).toPlainString());
            return line;
        }).toList());
        map.put("debts", debts.stream().map(d -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("debtorId", d.getDebtorId());
            line.put("creditorId", d.getCreditorId());
            line.put("originalAmount", d.getMoney().originalAmount().toPlainString());
            line.put("thbAmount", d.getMoney().thbAmount().toPlainString());
            line.put("status", d.getStatus().name());
            return line;
        }).toList());
        return map;
    }

    private static void putMoney(Map<String, Object> map, MoneyAmount money) {
        map.put("originalAmount", money.originalAmount().toPlainString());
        map.put("currency", money.currency());
        map.put("exchangeRate", Money.displayRate(money.exchangeRate()).toPlainString());
        map.put("thbAmount", money.thbAmount().toPlainString());
    }
}
