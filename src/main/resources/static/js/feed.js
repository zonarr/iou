// The rows shown in lists: an expense, a standalone IOU, a payment. Tap a row for details.
import { html, useState, cls } from './lib.js';
import { t, errorText, formatDay } from './i18n.js';
import { fmt, BASE } from './money.js';
import { post, del } from './api.js';
import { go } from './router.js';
import { Button, StatusChip, Amount, toast } from './ui.js';

/** YYYY-MM-DD in the phone's own time zone. */
export function localDay(iso) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function canEdit(item, me, group) {
  return item.createdBy?.id === me.id || (group && group.ownerId === me.id);
}

/** Runs an action (accept, delete...) with a busy state and an error toast. */
function useAction(onChanged) {
  const [busy, setBusy] = useState(false);
  const run = async (action) => {
    setBusy(true);
    try {
      await action();
      await onChanged?.();
    } catch (error) {
      toast(errorText(error));
    } finally {
      setBusy(false);
    }
  };
  return [busy, run];
}

function Answer({ busy, onYes, onNo, yes, no }) {
  return html`
    <div class="answer">
      <${Button} kind="ok" small busy=${busy} onClick=${(e) => { e.stopPropagation(); onYes(); }}>${yes}<//>
      <${Button} kind="ghost" small disabled=${busy} onClick=${(e) => { e.stopPropagation(); onNo(); }}>${no}<//>
    </div>`;
}

function Details({ item, rows, onEdit, onDelete, editable, busy }) {
  return html`
    <div class="details">
      ${rows}
      ${item.currency !== BASE ? html`<p class="muted">${t('feed.rate', { currency: item.currency, rate: item.exchangeRate })}</p>` : null}
      <p class="muted">${t('feed.recordedBy', { name: item.createdBy?.displayName })}</p>
      ${editable ? html`
        <div class="row-actions">
          <${Button} kind="ghost" small onClick=${(e) => { e.stopPropagation(); onEdit(); }}>${t('feed.edit')}<//>
          <${Button} kind="danger-ghost" small busy=${busy}
                     onClick=${(e) => { e.stopPropagation(); if (confirm(t('feed.confirmDelete'))) onDelete(); }}>${t('feed.delete')}<//>
        </div>` : null}
    </div>`;
}

export function ExpenseItem({ expense, me, group, onChanged, editPath }) {
  const [open, setOpen] = useState(false);
  const [busy, run] = useAction(onChanged);
  const mine = expense.debts.filter((d) => d.debtor.id === me.id);
  const owedToMe = expense.debts.filter((d) => d.creditor.id === me.id);
  const waiting = expense.debts.filter((d) => d.status === 'PENDING' && d.debtor.id !== me.id);
  const rejected = expense.debts.filter((d) => d.status === 'REJECTED');
  const payerText = expense.payers.length === 1
    ? t('feed.paidBy', { name: expense.payers[0].user.displayName })
    : t('feed.paidByMany', { n: expense.payers.length });
  const owedSum = owedToMe.reduce((sum, d) => sum + Number(d.thbAmount), 0);

  return html`
    <article class=${cls('item', open && 'open')} onClick=${() => setOpen(!open)}>
      <div class="item-main">
        <div class="item-text">
          <h3>${expense.description}</h3>
          <p class="muted">${payerText}</p>
        </div>
        <${Amount} original=${expense.originalAmount} currency=${expense.currency} thb=${expense.thbAmount} />
      </div>
      ${mine.map((d) => html`
        <div class="item-line owe" key=${d.id}>
          <span>${t('feed.youOwe', { name: d.creditor.displayName, amount: fmt(d.thbAmount) })}</span>
          <${StatusChip} status=${d.status} />
        </div>
        ${d.status === 'PENDING' ? html`
          <${Answer} busy=${busy} yes=${t('action.accept')} no=${t('action.reject')}
                     onYes=${() => run(() => post(`/api/debts/${d.id}/accept`))}
                     onNo=${() => run(() => post(`/api/debts/${d.id}/reject`))} />` : null}`)}
      ${owedToMe.length ? html`
        <div class="item-line get">
          <span>${owedToMe.length === 1
            ? t('feed.owesYou', { name: owedToMe[0].debtor.displayName, amount: fmt(owedSum) })
            : `${t('home.youAreOwed')} ${fmt(owedSum)}`}</span>
        </div>` : null}
      ${!mine.length && !owedToMe.length ? html`<div class="item-line muted">${t('feed.notInvolved')}</div>` : null}
      ${waiting.length ? html`<div class="item-line muted">⏳ ${t('feed.waitingFor', { names: waiting.map((d) => d.debtor.displayName).join(', ') })}</div>` : null}
      ${rejected.length ? html`<div class="item-line warn">${t('feed.rejectedBy', { names: rejected.map((d) => d.debtor.displayName).join(', ') })}</div>` : null}
      ${open ? html`
        <${Details} item=${expense} editable=${canEdit(expense, me, group)} busy=${busy}
          onEdit=${() => go(editPath)}
          onDelete=${() => run(() => del(`/api/expenses/${expense.id}`))}
          rows=${html`
            ${expense.payers.length > 1 ? html`
              <h4>${t('feed.payers')}</h4>
              <ul class="lines">${expense.payers.map((p) => html`
                <li key=${p.user.id}><span>${p.user.displayName}</span><span>${fmt(p.originalAmount, expense.currency)}</span></li>`)}</ul>` : null}
            <h4>${t('feed.shares')} · ${t(`split.${expense.splitType}`)}</h4>
            <ul class="lines">${expense.shares.map((s) => html`
              <li key=${s.user.id}>
                <span>${s.user.displayName}</span>
                <span>${fmt(s.originalAmount, expense.currency)}${expense.currency !== BASE ? html` <small>${fmt(s.thbAmount)}</small>` : null}</span>
              </li>`)}</ul>`} />` : null}
    </article>`;
}

export function DebtItem({ debt, me, group, onChanged, editPath }) {
  const [open, setOpen] = useState(false);
  const [busy, run] = useAction(onChanged);
  const iOwe = debt.debtor.id === me.id;
  return html`
    <article class=${cls('item', open && 'open')} onClick=${() => setOpen(!open)}>
      <div class="item-main">
        <div class="item-text">
          <h3>${debt.description}</h3>
          <p class="muted"><span class="tag">${t('feed.iou')}</span> ${t('feed.owes', { debtor: debt.debtor.displayName, creditor: debt.creditor.displayName })}</p>
        </div>
        <${Amount} original=${debt.originalAmount} currency=${debt.currency} thb=${debt.thbAmount} />
      </div>
      ${debt.status !== 'CONFIRMED' ? html`
        <div class="item-line">
          <span class="muted">${debt.status === 'PENDING' && !iOwe ? `⏳ ${t('feed.waitingFor', { names: debt.debtor.displayName })}` : ''}</span>
          <${StatusChip} status=${debt.status} />
        </div>` : null}
      ${iOwe && debt.status === 'PENDING' ? html`
        <${Answer} busy=${busy} yes=${t('action.accept')} no=${t('action.reject')}
                   onYes=${() => run(() => post(`/api/debts/${debt.id}/accept`))}
                   onNo=${() => run(() => post(`/api/debts/${debt.id}/reject`))} />` : null}
      ${open ? html`
        <${Details} item=${debt} editable=${!debt.expenseId && canEdit(debt, me, group)} busy=${busy}
          onEdit=${() => go(editPath)} onDelete=${() => run(() => del(`/api/debts/${debt.id}`))} />` : null}
    </article>`;
}

export function PaymentItem({ payment, me, group, onChanged, editPath }) {
  const [open, setOpen] = useState(false);
  const [busy, run] = useAction(onChanged);
  const toMe = payment.to.id === me.id;
  return html`
    <article class=${cls('item', 'payment', open && 'open')} onClick=${() => setOpen(!open)}>
      <div class="item-main">
        <div class="item-text">
          <h3>${t('feed.paid', { from: payment.from.displayName, to: payment.to.displayName })}</h3>
          <p class="muted"><span class="tag pay">${t('feed.payment')}</span> ${payment.note || ''}</p>
        </div>
        <${Amount} original=${payment.originalAmount} currency=${payment.currency} thb=${payment.thbAmount} />
      </div>
      ${payment.status !== 'CONFIRMED' ? html`
        <div class="item-line">
          <span class="muted">${payment.status === 'PENDING' && !toMe ? `⏳ ${t('feed.waitingFor', { names: payment.to.displayName })}` : ''}</span>
          <${StatusChip} status=${payment.status} />
        </div>` : null}
      ${toMe && payment.status === 'PENDING' ? html`
        <${Answer} busy=${busy} yes=${t('action.confirm')} no=${t('action.rejectPayment')}
                   onYes=${() => run(() => post(`/api/payments/${payment.id}/confirm`))}
                   onNo=${() => run(() => post(`/api/payments/${payment.id}/reject`))} />` : null}
      ${open ? html`
        <${Details} item=${payment} editable=${canEdit(payment, me, group)} busy=${busy}
          onEdit=${() => go(editPath)} onDelete=${() => run(() => del(`/api/payments/${payment.id}`))} />` : null}
    </article>`;
}

/** Merges expenses, standalone IOUs and payments into one list, newest day first, with day headings. */
export function Feed({ expenses, debts, payments, me, group, onChanged, paths, empty }) {
  const items = [
    ...expenses.map((e) => ({ kind: 'expense', key: `e${e.id}`, day: e.spentOn, at: e.createdAt, data: e })),
    ...debts.filter((d) => !d.expenseId).map((d) => ({ kind: 'debt', key: `d${d.id}`, day: localDay(d.createdAt), at: d.createdAt, data: d })),
    ...payments.map((p) => ({ kind: 'payment', key: `p${p.id}`, day: localDay(p.createdAt), at: p.createdAt, data: p })),
  ].sort((a, b) => b.day.localeCompare(a.day) || b.at.localeCompare(a.at));

  if (!items.length) return html`<p class="empty">${empty}</p>`;
  let lastDay = null;
  return html`
    <div class="feed">
      ${items.map((item) => {
        const heading = item.day !== lastDay ? html`<h2 class="day" key=${`h${item.day}`}>${formatDay(item.day)}</h2>` : null;
        lastDay = item.day;
        const row = item.kind === 'expense'
          ? html`<${ExpenseItem} key=${item.key} expense=${item.data} me=${me} group=${group} onChanged=${onChanged} editPath=${paths.expense(item.data)} />`
          : item.kind === 'debt'
            ? html`<${DebtItem} key=${item.key} debt=${item.data} me=${me} group=${group} onChanged=${onChanged} editPath=${paths.debt(item.data)} />`
            : html`<${PaymentItem} key=${item.key} payment=${item.data} me=${me} group=${group} onChanged=${onChanged} editPath=${paths.payment(item.data)} />`;
        return [heading, row];
      })}
    </div>`;
}
