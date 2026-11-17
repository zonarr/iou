// The three entry forms: expense, simple IOU, payment. Used inside a group and one-on-one.
import { html, useState, useEffect, useLoad, useStore } from './lib.js';
import { t } from './i18n.js';
import { fmt, fromThb, validAmount, validRate, cleanNumber, scaled, unscaled, BASE } from './money.js';
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

const SPLIT_TYPES = ['EQUAL', 'EXACT', 'PERCENT', 'SHARES'];

/** One line per person with a number box: used for payer amounts and for custom splits. */
function ValueRows({ people, values, onChange, unit, placeholder }) {
  return html`
    <div class="value-rows">
      ${people.map((p) => html`
        <label class="value-row" key=${p.id}>
          <span>${p.displayName}</span>
          <input class="input" inputmode="decimal" autocomplete="off" placeholder=${placeholder}
                 value=${values[p.id] ?? ''} onInput=${(e) => onChange({ ...values, [p.id]: e.target.value })} />
          <span class="unit">${unit}</span>
        </label>`)}
    </div>`;
}

/** "x left / x too much / adds up" under a list of amounts that must reach the total. */
function TotalCheck({ total, parts, currency, left, over, ok }) {
  const target = scaled(total);
  if (target === null) return null;
  let sum = 0n;
  for (const part of parts) sum += scaled(part) ?? 0n;
  const difference = target - sum;
  if (difference === 0n) return html`<p class="check ok">✓ ${t(ok)}</p>`;
  return html`<p class="check bad">${t(difference > 0n ? left : over,
    { amount: fmt(unscaled(difference > 0n ? difference : -difference), currency) })}</p>`;
}

function PercentCheck({ parts }) {
  let sum = 0n;
  for (const part of parts) sum += scaled(part, 4) ?? 0n;
  const difference = 1000000n - sum; // 100 at scale 4
  if (difference === 0n) return html`<p class="check ok">✓ ${t('split.percentOk')}</p>`;
  const n = unscaled(difference > 0n ? difference : -difference, 4).replace(/\.?0+$/, '');
  return html`<p class="check bad">${t(difference > 0n ? 'split.percentLeft' : 'split.percentOver', { n })}</p>`;
}

function ExpenseFields({ ctx, me, query, backTo }) {
  const { group, members, rates, existing } = ctx;
  const everyone = members.map((m) => m.id);
  const [description, setDescription] = useState(existing?.description || '');
  const [money, setMoney] = useState(existing ? moneyFrom(existing) : emptyMoney());
  const [spentOn, setSpentOn] = useState(existing?.spentOn || localDay(new Date().toISOString()));

  // who paid
  const [multiPayer, setMultiPayer] = useState(existing ? existing.payers.length > 1 : false);
  const [payerId, setPayerId] = useState(existing ? existing.payers[0].user.id : Number(query.payer) || me.id);
  const [payerIds, setPayerIds] = useState(existing ? existing.payers.map((p) => p.user.id) : [me.id]);
  const [payerAmounts, setPayerAmounts] = useState(existing
    ? Object.fromEntries(existing.payers.map((p) => [p.user.id, Number(p.originalAmount).toFixed(2)])) : {});

  // who it was for, and how it is divided
  const [participantIds, setParticipantIds] = useState(existing ? existing.shares.map((s) => s.user.id) : everyone);
  const [splitType, setSplitType] = useState(existing?.splitType || 'EQUAL');
  const [values, setValues] = useState(existing && existing.splitType !== 'EQUAL'
    ? Object.fromEntries(existing.shares.map((s) => [s.user.id,
      existing.splitType === 'EXACT' ? Number(s.originalAmount).toFixed(2) : String(s.weight)])) : {});

  const options = memberOptions(members, me);
  const byId = (ids) => members.filter((m) => ids.includes(m.id));
  const changeSplitType = (type) => {
    setSplitType(type);
    setValues(type === 'SHARES' ? Object.fromEntries(everyone.map((id) => [id, '1'])) : {});
  };
  const changeParticipants = (ids) => {
    setParticipantIds(ids);
    if (splitType === 'SHARES') {
      setValues(Object.fromEntries(ids.map((id) => [id, values[id] || '1'])));
    }
  };

  const { busy, error, submit } = useSubmit(() => {
    if (!description.trim()) fail('description_required');
    const problem = moneyProblem(money);
    if (problem) fail(problem);
    const total = scaled(cleanNumber(money.amount));
    const body = { description: description.trim(), ...moneyBody(money), spentOn };

    if (multiPayer) {
      if (!payerIds.length) fail('payers_required');
      let paid = 0n;
      for (const id of payerIds) {
        if (!validAmount(payerAmounts[id])) fail('payers_mismatch');
        paid += scaled(cleanNumber(payerAmounts[id]));
      }
      if (paid !== total) fail('payers_mismatch');
      body.payers = payerIds.map((id) => ({ userId: id, amount: cleanNumber(payerAmounts[id]) }));
    } else {
      body.payerId = payerId;
    }

    if (!participantIds.length) fail('participants_required');
    if (splitType === 'EQUAL') {
      body.participantIds = participantIds;
    } else {
      const numbers = participantIds.map((id) => cleanNumber(values[id]));
      if (numbers.some((n) => !n || Number(n) <= 0)) fail('split_value_required');
      if (splitType === 'EXACT') {
        if (numbers.some((n) => scaled(n) === null)) fail('amount_too_precise');
        if (numbers.reduce((sum, n) => sum + scaled(n), 0n) !== total) fail('split_mismatch');
      } else if (numbers.some((n) => scaled(n, 4) === null)) {
        fail('split_value_invalid');
      } else if (splitType === 'PERCENT' && numbers.reduce((sum, n) => sum + scaled(n, 4), 0n) !== 1000000n) {
        fail('percent_mismatch');
      }
      body.splitType = splitType;
      body.shares = participantIds.map((id, i) => ({ userId: id, value: numbers[i] }));
    }
    return () => (existing
      ? put(`/api/expenses/${existing.id}`, body)
      : post(group ? `/api/groups/${group.id}/expenses` : '/api/expenses', body));
  }, backTo);

  const each = splitType === 'EQUAL' && validAmount(money.amount) && participantIds.length
    ? fmt(Number(cleanNumber(money.amount)) / participantIds.length, money.currency) : null;
  const othersMustAccept = !group?.autoConfirm;
  const unit = { EXACT: money.currency, PERCENT: '%', SHARES: '×' }[splitType];

  return html`
    <form class="form" onSubmit=${submit}>
      <${Field} label=${t('form.description')}>
        <${TextInput} value=${description} onInput=${setDescription} maxlength="200"
                      placeholder=${t('form.descriptionHint')} autofocus=${!existing} />
      <//>
      <${MoneyFields} value=${money} onChange=${setMoney} rates=${rates} />

      <${Section} label=${t('form.paidBy')}
        action=${html`<button type="button" class="link" onClick=${() => setMultiPayer(!multiPayer)}>
          ${multiPayer ? t('split.singlePayer') : t('split.multiPayer')}</button>`}>
        ${multiPayer ? html`
          <${Chips} multi options=${options} value=${payerIds} onChange=${setPayerIds} />
          <${ValueRows} people=${byId(payerIds)} values=${payerAmounts} onChange=${setPayerAmounts}
                        unit=${money.currency} placeholder="0.00" />
          <${TotalCheck} total=${cleanNumber(money.amount)} currency=${money.currency}
                         parts=${payerIds.map((id) => cleanNumber(payerAmounts[id]))}
                         left="split.payersLeft" over="split.payersOver" ok="split.payersOk" />`
        : html`<${Chips} options=${options} value=${payerId} onChange=${setPayerId} />`}
      <//>

      <${Section} label=${t('form.splitBetween')} hint=${each ? t('form.eachPays', { amount: each }) : null}
        action=${html`<button type="button" class="link" onClick=${() =>
          changeParticipants(participantIds.length === members.length ? [] : everyone)}>
          ${participantIds.length === members.length ? t('form.nobody') : t('form.everyone')}</button>`}>
        <${Chips} multi options=${options} value=${participantIds} onChange=${changeParticipants} />
      <//>

      <${Section} label=${t('split.title')} hint=${splitType === 'SHARES' ? t('split.sharesHint') : null}>
        <div class="tabs" role="radiogroup" aria-label=${t('split.title')}>
          ${SPLIT_TYPES.map((type) => html`
            <button type="button" key=${type} role="radio" aria-checked=${splitType === type}
                    class=${splitType === type ? 'on' : ''} onClick=${() => changeSplitType(type)}>${t(`split.${type}`)}</button>`)}
        </div>
        ${splitType !== 'EQUAL' ? html`
          <${ValueRows} people=${byId(participantIds)} values=${values} onChange=${setValues} unit=${unit}
                        placeholder=${splitType === 'SHARES' ? '1' : '0'} />
          ${splitType === 'EXACT' ? html`
            <${TotalCheck} total=${cleanNumber(money.amount)} currency=${money.currency}
                           parts=${participantIds.map((id) => cleanNumber(values[id]))}
                           left="split.left" over="split.over" ok="split.exactOk" />` : null}
          ${splitType === 'PERCENT' ? html`
            <${PercentCheck} parts=${participantIds.map((id) => cleanNumber(values[id]))} />` : null}` : null}
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
