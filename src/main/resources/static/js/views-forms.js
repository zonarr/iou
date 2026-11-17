// The three entry forms: expense, simple IOU, payment. Used inside a group and one-on-one.
import { html, useState, useEffect, useLoad, useStore } from './lib.js';
import { t } from './i18n.js';
import { fmt, fromThb, validAmount, validRate, BASE } from './money.js';
import { get, post, put, session } from './api.js';
import { back } from './router.js';
import { Page, Loaded, Button, Field, Section, TextInput, Chips, MoneyFields, ErrorBox,
  moneyProblem, moneyBody, emptyMoney, moneyFrom, localError } from './ui.js';
import { localDay } from './feed.js';

/**
 * Everything a form needs to know about where it is: the group (or none), the people who
 * can be chosen, and the remembered exchange rates.
 */
async function loadContext(groupId, me, query, existingPath, peopleOf) {
  const existing = existingPath ? await get(existingPath) : null;
  if (groupId) {
    const [group, rates] = await Promise.all([get(`/api/groups/${groupId}`), get(`/api/groups/${groupId}/rates`)]);
    return { group, members: group.members, rates, existing };
  }
  const members = new Map([[me.id, { id: me.id, displayName: me.displayName }]]);
  if (query.with) members.set(Number(query.with), { id: Number(query.with), displayName: query.name || '?' });
  if (existing) peopleOf(existing).forEach((p) => members.set(p.id, p));
  return { group: null, members: [...members.values()], rates: await get('/api/me/rates'), existing };
}

function memberOptions(members, me) {
  return members.map((m) => ({ value: m.id, label: m.displayName, sub: m.id === me.id ? t('form.you') : null }));
}

function useSubmit(action, backTo) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const submit = async (event) => {
    event?.preventDefault();
    setError(null);
    let request;
    try {
      request = action();          // throws a localError for anything the form can check itself
    } catch (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    try {
      await request();
      back(backTo);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  };
  return { busy, error, submit };
}

function fail(code) {
  throw localError(code);
}

// ---------------------------------------------------------------- expense

export function ExpenseForm({ groupId, expenseId, query }) {
  const { user } = useStore(session);
  const backTo = groupId ? `/g/${groupId}` : '/direct';
  const load = useLoad(() => loadContext(groupId, user, query, expenseId && `/api/expenses/${expenseId}`,
    (e) => [...e.payers.map((p) => p.user), ...e.shares.map((s) => s.user)]), [groupId, expenseId]);
  return html`
    <${Page} title=${expenseId ? t('form.editExpense') : t('form.newExpense')} backTo=${backTo}>
      <${Loaded} load=${load}>${(ctx) => html`<${ExpenseFields} ctx=${ctx} me=${user} query=${query} backTo=${backTo} />`}<//>
    <//>`;
}

function ExpenseFields({ ctx, me, query, backTo }) {
  const { group, members, rates, existing } = ctx;
  const [description, setDescription] = useState(existing?.description || '');
  const [money, setMoney] = useState(existing ? moneyFrom(existing) : emptyMoney());
  const [payerId, setPayerId] = useState(existing ? existing.payers[0].user.id : Number(query.payer) || me.id);
  const [participantIds, setParticipantIds] = useState(
    existing ? existing.shares.map((s) => s.user.id) : members.map((m) => m.id));
  const [spentOn, setSpentOn] = useState(existing?.spentOn || localDay(new Date().toISOString()));
  const options = memberOptions(members, me);

  const { busy, error, submit } = useSubmit(() => {
    if (!description.trim()) fail('description_required');
    const problem = moneyProblem(money);
    if (problem) fail(problem);
    if (!participantIds.length) fail('participants_required');
    const body = { description: description.trim(), ...moneyBody(money), spentOn, payerId, participantIds };
    return () => (existing
      ? put(`/api/expenses/${existing.id}`, body)
      : post(group ? `/api/groups/${group.id}/expenses` : '/api/expenses', body));
  }, backTo);

  const each = validAmount(money.amount) && participantIds.length
    ? fmt(Number(money.amount.replace(',', '.')) / participantIds.length, money.currency) : null;
  const othersMustAccept = !group?.autoConfirm;

  return html`
    <form class="form" onSubmit=${submit}>
      <${Field} label=${t('form.description')}>
        <${TextInput} value=${description} onInput=${setDescription} maxlength="200"
                      placeholder=${t('form.descriptionHint')} autofocus=${!existing} />
      <//>
      <${MoneyFields} value=${money} onChange=${setMoney} rates=${rates} />
      <${Section} label=${t('form.paidBy')}>
        <${Chips} options=${options} value=${payerId} onChange=${setPayerId} />
      <//>
      <${Section} label=${t('form.splitBetween')} hint=${each ? t('form.eachPays', { amount: each }) : null}
        action=${html`<button type="button" class="link" onClick=${() =>
          setParticipantIds(participantIds.length === members.length ? [] : members.map((m) => m.id))}>
          ${participantIds.length === members.length ? t('form.nobody') : t('form.everyone')}</button>`}>
        <${Chips} multi options=${options} value=${participantIds} onChange=${setParticipantIds} />
      <//>
      <${Field} label=${t('form.date')}>
        <input class="input" type="date" value=${spentOn} onInput=${(e) => setSpentOn(e.target.value)} />
      <//>
      ${othersMustAccept ? html`<p class="muted">${existing ? t('form.editPendWarning') : t('form.willPend')}</p>` : null}
      <${ErrorBox} error=${error} />
      <div class="actions-bar">
        <${Button} type="submit" busy=${busy}>${t('action.save')}<//>
      </div>
    </form>`;
}

// ---------------------------------------------------------------- simple IOU

export function IouForm({ groupId, debtId, query }) {
  const { user } = useStore(session);
  const backTo = groupId ? `/g/${groupId}` : '/direct';
  const load = useLoad(() => loadContext(groupId, user, query, debtId && `/api/debts/${debtId}`,
    (d) => [d.debtor, d.creditor]), [groupId, debtId]);
  return html`
    <${Page} title=${debtId ? t('form.editIou') : t('form.newIou')} backTo=${backTo}>
      <${Loaded} load=${load}>${(ctx) => html`<${IouFields} ctx=${ctx} me=${user} query=${query} backTo=${backTo} />`}<//>
    <//>`;
}

function IouFields({ ctx, me, query, backTo }) {
  const { group, members, rates, existing } = ctx;
  const other = members.find((m) => m.id !== me.id)?.id ?? null;
  const [description, setDescription] = useState(existing?.description || '');
  const [money, setMoney] = useState(existing ? moneyFrom(existing) : emptyMoney());
  const [debtorId, setDebtorId] = useState(existing ? existing.debtor.id : Number(query.debtor) || me.id);
  const [creditorId, setCreditorId] = useState(existing ? existing.creditor.id
    : (Number(query.debtor) || me.id) === me.id ? (members.length === 2 ? other : null) : me.id);
  const options = memberOptions(members, me);

  const { busy, error, submit } = useSubmit(() => {
    if (!debtorId || !creditorId) fail('pick_person');
    if (debtorId === creditorId) fail('same_person');
    const problem = moneyProblem(money);
    if (problem) fail(problem);
    if (!description.trim()) fail('description_required');
    const body = { debtorId, creditorId, ...moneyBody(money), description: description.trim() };
    return () => (existing
      ? put(`/api/debts/${existing.id}`, body)
      : post(group ? `/api/groups/${group.id}/debts` : '/api/debts', body));
  }, backTo);

  return html`
    <form class="form" onSubmit=${submit}>
      <${Section} label=${t('form.whoOwes')}>
        <${Chips} options=${options} value=${debtorId} onChange=${setDebtorId} />
      <//>
      <${Section} label=${t('form.toWhom')}>
        <${Chips} options=${options.filter((o) => o.value !== debtorId)} value=${creditorId} onChange=${setCreditorId} />
      <//>
      <${MoneyFields} value=${money} onChange=${setMoney} rates=${rates} />
      <${Field} label=${t('form.description')}>
        <${TextInput} value=${description} onInput=${setDescription} maxlength="200" placeholder=${t('form.descriptionHint')} />
      <//>
      <${ErrorBox} error=${error} />
      <div class="actions-bar">
        <${Button} type="submit" busy=${busy}>${t('action.save')}<//>
      </div>
    </form>`;
}

// ---------------------------------------------------------------- payment

export function PaymentForm({ groupId, paymentId, query }) {
  const { user } = useStore(session);
  const backTo = groupId ? `/g/${groupId}/balances` : '/direct';
  const load = useLoad(() => loadContext(groupId, user, query, paymentId && `/api/payments/${paymentId}`,
    (p) => [p.from, p.to]), [groupId, paymentId]);
  return html`
    <${Page} title=${paymentId ? t('form.editPayment') : t('form.newPayment')} backTo=${backTo}>
      <${Loaded} load=${load}>${(ctx) => html`<${PaymentFields} ctx=${ctx} me=${user} query=${query} backTo=${backTo} />`}<//>
    <//>`;
}

function PaymentFields({ ctx, me, query, backTo }) {
  const { group, members, rates, existing } = ctx;
  const other = members.find((m) => m.id !== me.id)?.id ?? null;
  const suggestedThb = !existing && query.amount && validAmount(query.amount) ? query.amount : null;
  const [money, setMoney] = useState(existing ? moneyFrom(existing)
    : { ...emptyMoney(), amount: suggestedThb || '' });
  const [fromId, setFromId] = useState(existing ? existing.from.id : Number(query.from) || me.id);
  const [toId, setToId] = useState(existing ? existing.to.id
    : Number(query.to) || ((Number(query.from) || me.id) === me.id ? (members.length === 2 ? other : null) : me.id));
  const [note, setNote] = useState(existing?.note || '');
  const options = memberOptions(members, me);

  // Paying a THB suggestion in another currency: offer the matching foreign amount.
  const suggestedForeign = suggestedThb && money.currency !== BASE && validRate(money.rate)
    ? fromThb(suggestedThb, money.rate) : null;
  useEffect(() => {
    if (suggestedThb && money.currency === BASE && !money.amount) setMoney({ ...money, amount: suggestedThb });
  }, [money.currency]);

  const { busy, error, submit } = useSubmit(() => {
    if (!fromId || !toId) fail('pick_person');
    if (fromId === toId) fail('same_person');
    const problem = moneyProblem(money);
    if (problem) fail(problem);
    const body = { groupId: group?.id ?? null, fromUserId: fromId, toUserId: toId, ...moneyBody(money), note: note.trim() || null };
    return () => (existing ? put(`/api/payments/${existing.id}`, body) : post('/api/payments', body));
  }, backTo);

  const receiver = members.find((m) => m.id === toId);
  const needsConfirm = receiver && receiver.id !== me.id && !receiver.placeholder && !group?.autoConfirm;

  return html`
    <form class="form" onSubmit=${submit}>
      <${Section} label=${t('form.whoPaid')}>
        <${Chips} options=${options} value=${fromId} onChange=${setFromId} />
      <//>
      <${Section} label=${t('form.whoReceived')}>
        <${Chips} options=${options.filter((o) => o.value !== fromId)} value=${toId} onChange=${setToId} />
      <//>
      <${MoneyFields} value=${money} onChange=${setMoney} rates=${rates} />
      ${suggestedForeign && suggestedForeign !== money.amount ? html`
        <button type="button" class="link" onClick=${() => setMoney({ ...money, amount: suggestedForeign })}>
          ${t('form.suggested', { amount: fmt(suggestedForeign, money.currency) })}
        </button>` : null}
      <${Field} label=${t('form.note')}>
        <${TextInput} value=${note} onInput=${setNote} maxlength="200" placeholder=${t('form.noteHint')} />
      <//>
      ${needsConfirm ? html`<p class="muted">${t('form.willPendPayment', { name: receiver.displayName })}</p>` : null}
      <${ErrorBox} error=${error} />
      <div class="actions-bar">
        <${Button} type="submit" busy=${busy}>${t('action.save')}<//>
      </div>
    </form>`;
}
