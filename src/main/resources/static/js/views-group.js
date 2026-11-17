// One group: expenses feed, balances + settle-up, people.
import { html, useState, useEffect, useLoad, useStore, cls } from './lib.js';
import { t, errorText } from './i18n.js';
import { fmt } from './money.js';
import { get, post, patch, del, session } from './api.js';
import { go, replace, query } from './router.js';
import { Page, Loaded, Button, Field, TextInput, ErrorBox, toast } from './ui.js';
import { Feed } from './feed.js';

const TABS = ['feed', 'balances', 'people'];

export function Group({ id, tab = 'feed' }) {
  const { user } = useStore(session);
  const load = useLoad(() => get(`/api/groups/${id}`), [id]);
  return html`
    <${Page} title=${load.data?.name || '…'} backTo="/"
             subtitle=${load.data?.autoConfirm ? `✓ ${t('group.trustedBadge')}` : null}>
      <nav class="tabs" role="tablist">
        ${TABS.map((name) => html`
          <button key=${name} role="tab" aria-selected=${tab === name} class=${cls(tab === name && 'on')}
                  onClick=${() => replace(`/g/${id}${name === 'feed' ? '' : `/${name}`}`)}>${t(`group.tab.${name}`)}</button>`)}
      </nav>
      <${Loaded} load=${load}>${(group) =>
        tab === 'balances' ? html`<${BalancesTab} group=${group} me=${user} />`
        : tab === 'people' ? html`<${PeopleTab} group=${group} me=${user} onChanged=${load.reload} />`
        : html`<${FeedTab} group=${group} me=${user} />`}<//>
    <//>`;
}

function FeedTab({ group, me }) {
  const base = `/g/${group.id}`;
  const load = useLoad(async () => {
    const [expenses, debts, payments] = await Promise.all([
      get(`/api/groups/${group.id}/expenses`), get(`/api/groups/${group.id}/debts`), get(`/api/groups/${group.id}/payments`)]);
    return { expenses, debts, payments };
  }, [group.id]);
  return html`
    <${Loaded} load=${load}>${(data) => html`
      <${Feed} ...${data} me=${me} group=${group} onChanged=${load.reload} empty=${t('group.empty')}
        paths=${{
          expense: (e) => `${base}/expense/${e.id}`,
          debt: (d) => `${base}/iou/${d.id}`,
          payment: (p) => `${base}/pay/${p.id}`,
        }} />`}<//>
    <div class="actions-bar">
      <${Button} onClick=${() => go(`${base}/expense`)}>＋ ${t('group.addExpense')}<//>
      <div class="btn-row">
        <${Button} kind="secondary" small onClick=${() => go(`${base}/pay`)}>${t('group.recordPayment')}<//>
        <${Button} kind="secondary" small onClick=${() => go(`${base}/iou`)}>${t('group.addIou')}<//>
      </div>
    </div>`;
}

function BalancesTab({ group, me }) {
  const load = useLoad(async () => {
    const [balances, plan] = await Promise.all([
      get(`/api/groups/${group.id}/balances`), get(`/api/groups/${group.id}/settle-up`)]);
    return { balances, plan };
  }, [group.id]);
  return html`
    <${Loaded} load=${load}>${({ balances, plan }) => {
      const pending = balances.pendingDebts + balances.pendingPayments;
      return html`
        <h2 class="section-title">${t('balances.title')}</h2>
        <ul class="lines card balances">
          ${balances.balances.map((b) => {
            const n = Number(b.net);
            return html`
              <li key=${b.user.id}>
                <span>${b.user.displayName}${b.user.id === me.id ? html` <small class="muted">(${t('form.you')})</small>` : null}</span>
                <span class=${n > 0 ? 'pos' : n < 0 ? 'neg' : 'muted'}>
                  <small>${n > 0 ? t('balances.getsBack') : n < 0 ? t('balances.owes') : t('balances.settled')}</small>
                  ${n !== 0 ? html` <b>${fmt(Math.abs(n))}</b>` : null}
                </span>
              </li>`;
          })}
        </ul>
        ${pending ? html`<p class="notice">⏳ ${pending === 1 ? t('balances.pendingNote.one') : t('balances.pendingNote.many', { n: pending })}</p>` : null}

        <h2 class="section-title">${t('settle.title')}</h2>
        <p class="muted">${t('settle.hint')}</p>
        ${plan.transfers.length ? html`
          <div class="stack">
            ${plan.transfers.map((tr) => html`
              <div class=${cls('card transfer', (tr.from.id === me.id || tr.to.id === me.id) && 'mine')} key=${`${tr.from.id}-${tr.to.id}`}>
                <div class="transfer-line">
                  <span>${t('settle.pays', { from: tr.from.displayName, to: tr.to.displayName })}</span>
                  <b>${fmt(tr.amount)}</b>
                </div>
                <${Button} kind="secondary" small
                  onClick=${() => go(`/g/${group.id}/pay${query({ from: tr.from.id, to: tr.to.id, amount: Number(tr.amount).toFixed(2) })}`)}>
                  ${t('settle.record')}
                <//>
              </div>`)}
          </div>` : html`<p class="empty">✓ ${t('settle.none')}</p>`}`;
    }}<//>`;
}

function PeopleTab({ group, me, onChanged }) {
  const isOwner = group.ownerId === me.id;
  const [name, setName] = useState('');
  const [byEmail, setByEmail] = useState(false);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState(null);
  const inviteUrl = `${location.origin}/#/join/${group.inviteCode}`;
  // The switch moves at once and falls back if the server refuses.
  const [trusted, setTrusted] = useState(group.autoConfirm);
  useEffect(() => setTrusted(group.autoConfirm), [group.autoConfirm]);

  const act = async (key, action) => {
    setBusy(key);
    setError(null);
    try {
      await action();
      await onChanged();
    } catch (e) {
      setError(e);
    } finally {
      setBusy('');
    }
  };

  const add = (event) => {
    event.preventDefault();
    act('add', async () => {
      await post(`/api/groups/${group.id}/members`, byEmail ? { email: name } : { name });
      setName('');
    });
  };
  const share = async () => {
    const text = t('people.shareText', { group: group.name });
    if (navigator.share) {
      try { await navigator.share({ title: 'iou', text, url: inviteUrl }); } catch { /* cancelled */ }
    } else {
      copy();
    }
  };
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(inviteUrl);
      toast(t('people.copied'));
    } catch {
      prompt(t('people.copy'), inviteUrl);
    }
  };

  return html`
    <section class="card form">
      <h2>${t('people.invite')}</h2>
      <p class="muted">${t('people.inviteHint')}</p>
      <input class="input mono" readonly value=${inviteUrl} onFocus=${(e) => e.target.select()} aria-label=${t('people.invite')} />
      <div class="btn-row">
        <${Button} onClick=${share}>${t('people.share')}<//>
        <${Button} kind="secondary" onClick=${copy}>${t('people.copy')}<//>
      </div>
      ${isOwner ? html`
        <button class="link" onClick=${() => confirm(t('people.resetConfirm')) && act('reset', () => post(`/api/groups/${group.id}/invite/reset`))}>
          ${t('people.resetLink')}
        </button>` : null}
    </section>

    <h2 class="section-title">${t('people.title')} (${group.members.length})</h2>
    <ul class="card people">
      ${group.members.map((m) => html`
        <li key=${m.id}>
          <div class="person">
            <span class=${cls('avatar', m.placeholder && 'alt')}>${m.displayName.trim().charAt(0).toUpperCase()}</span>
            <span class="list-text">
              <b>${m.displayName}</b>
              <small>${[m.id === me.id && t('people.you'), m.id === group.ownerId && t('people.owner'), m.placeholder && t('people.placeholder')].filter(Boolean).join(' · ')}</small>
            </span>
          </div>
          ${m.placeholder ? html`
            <div class="row-actions">
              <${Button} kind="ghost" small busy=${busy === `claim${m.id}`}
                onClick=${() => confirm(t('people.thisIsMeConfirm', { name: m.displayName })) && act(`claim${m.id}`, () => post(`/api/placeholders/${m.id}/claim`))}>
                ${t('people.thisIsMe')}<//>
              <${Button} kind="ghost" small onClick=${() => {
                const next = prompt(t('people.rename'), m.displayName);
                if (next && next.trim()) act(`rename${m.id}`, () => patch(`/api/groups/${group.id}/members/${m.id}`, { name: next.trim() }));
              }}>${t('people.rename')}<//>
              <${Button} kind="danger-ghost" small busy=${busy === `remove${m.id}`}
                onClick=${() => confirm(t('people.removeConfirm', { name: m.displayName })) && act(`remove${m.id}`, () => del(`/api/groups/${group.id}/members/${m.id}`))}>
                ${t('people.remove')}<//>
            </div>` : null}
          ${!m.placeholder && m.id !== group.ownerId && (isOwner || m.id === me.id) ? html`
            <div class="row-actions">
              <${Button} kind="danger-ghost" small busy=${busy === `remove${m.id}`}
                onClick=${() => confirm(t('people.removeConfirm', { name: m.displayName })) && act(`remove${m.id}`, async () => {
                  await del(`/api/groups/${group.id}/members/${m.id}`);
                  if (m.id === me.id) replace('/');
                })}>
                ${m.id === me.id ? t('people.leave') : t('people.remove')}<//>
            </div>` : null}
        </li>`)}
    </ul>
    <${ErrorBox} error=${error} />

    <form class="card form" onSubmit=${add}>
      <h2>${t('people.addTitle')}</h2>
      <${Field} label=${byEmail ? t('auth.email') : t('people.addName')} hint=${byEmail ? null : t('people.addHint')}>
        <${TextInput} value=${name} onInput=${setName} required maxlength=${byEmail ? 254 : 80}
                      type=${byEmail ? 'email' : 'text'} placeholder=${byEmail ? '' : t('people.addNameHint')} />
      <//>
      <${Button} kind="secondary" type="submit" busy=${busy === 'add'}>＋ ${t('action.add')}<//>
      <button type="button" class="link" onClick=${() => { setByEmail(!byEmail); setName(''); }}>
        ${byEmail ? t('people.addByName') : t('people.addByEmail')}
      </button>
    </form>

    <section class="card form">
      <label class="switch">
        <input type="checkbox" checked=${trusted} disabled=${!isOwner || busy === 'trust'}
               onChange=${(e) => {
                 const on = e.target.checked;
                 setTrusted(on);
                 act('trust', () => patch(`/api/groups/${group.id}`, { autoConfirm: on }).catch((error) => {
                   setTrusted(!on);
                   throw error;
                 }));
               }} />
        <span class="switch-text">
          <b>${t('people.trusted')}</b>
          <small>${t('people.trustedHint')}</small>
          ${!isOwner ? html`<small>${t('people.trustedOwnerOnly')}</small>` : null}
        </span>
      </label>
      ${isOwner ? html`
        <button class="link" onClick=${() => {
          const next = prompt(t('people.groupName'), group.name);
          if (next && next.trim()) act('name', () => patch(`/api/groups/${group.id}`, { name: next.trim() }));
        }}>${t('people.groupName')}: ${group.name} ✎</button>` : null}
    </section>`;
}
