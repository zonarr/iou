// Home, "needs your answer", profile, and joining through an invite link.
import { html, useState, useLoad, useStore, cls } from './lib.js';
import { t, errorText, language } from './i18n.js';
import { fmt } from './money.js';
import { get, post, patch, session, setSession, logout } from './api.js';
import { go, replace } from './router.js';
import { Page, Loaded, Button, Field, TextInput, ErrorBox, toast } from './ui.js';
import { DebtItem, PaymentItem } from './feed.js';
import { LanguageSwitch, rememberInvite } from './views-auth.js';

function NetLine({ net }) {
  const n = Number(net);
  if (n === 0) return html`<span class="muted">${t('home.even')}</span>`;
  return n < 0
    ? html`<span class="neg">${t('home.youOweShort', { amount: fmt(-n) })}</span>`
    : html`<span class="pos">${t('home.owedShort', { amount: fmt(n) })}</span>`;
}

export function Home() {
  const load = useLoad(async () => {
    const [mine, groups, pending] = await Promise.all([
      get('/api/me/settle-up'), get('/api/groups'), get('/api/me/pending')]);
    return { mine, groups, pending };
  });
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const create = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const group = await post('/api/groups', { name });
      go(`/g/${group.id}/people`);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return html`
    <${Page} title=${t('nav.home')}>
      <${Loaded} load=${load}>${({ mine, groups, pending }) => {
        const net = Number(mine.net);
        const waiting = pending.debts.length + pending.payments.length;
        const netByGroup = Object.fromEntries(mine.contexts.map((c) => [c.groupId ?? 'direct', c.net]));
        return html`
          <section class=${cls('hero', net < 0 ? 'neg' : net > 0 ? 'pos' : 'zero')}>
            ${net === 0 && !mine.transfers.length ? html`
              <p class="hero-label">${t('home.settled')}</p>
              <p class="hero-sub">${t('home.settledHint')}</p>` : html`
              <p class="hero-label">${net < 0 ? t('home.youOwe') : t('home.youAreOwed')} <small>${t('home.inTotal')}</small></p>
              <p class="hero-amount">${fmt(Math.abs(net))}</p>
              <div class="hero-split">
                <span>${t('home.youOwe')} <b>${fmt(mine.totalOwedByMe)}</b></span>
                <span>${t('home.youAreOwed')} <b>${fmt(mine.totalOwedToMe)}</b></span>
              </div>`}
          </section>

          ${waiting ? html`
            <button class="callout" onClick=${() => go('/pending')}>
              <span class="dot">${waiting}</span>
              <span>${waiting === 1 ? t('home.pending.one') : t('home.pending.many', { n: waiting })}</span>
              <span class="chev">›</span>
            </button>` : null}

          <h2 class="section-title">${t('home.groups')}</h2>
          ${groups.length ? html`
            <div class="list">
              ${groups.map((g) => html`
                <button class="list-row" key=${g.id} onClick=${() => go(`/g/${g.id}`)}>
                  <span class="avatar">${g.name.trim().charAt(0).toUpperCase()}</span>
                  <span class="list-text">
                    <b>${g.name}</b>
                    <small>${t('home.members', { n: g.memberCount })} · <${NetLine} net=${netByGroup[g.id] ?? 0} /></small>
                  </span>
                  <span class="chev">›</span>
                </button>`)}
            </div>` : html`<p class="empty">${t('home.noGroups')}</p>`}

          ${creating ? html`
            <form class="card form" onSubmit=${create}>
              <${Field} label=${t('home.groupName')} hint=${t('home.groupNameHint')}>
                <${TextInput} value=${name} onInput=${setName} required maxlength="80" autofocus />
              <//>
              <${ErrorBox} error=${error} />
              <div class="btn-row">
                <${Button} kind="ghost" onClick=${() => setCreating(false)}>${t('action.cancel')}<//>
                <${Button} type="submit" busy=${busy}>${t('home.create')}<//>
              </div>
            </form>` : html`
            <${Button} kind="secondary" onClick=${() => setCreating(true)}>＋ ${t('home.newGroup')}<//>`}

          <div class="list">
            <button class="list-row" onClick=${() => go('/direct')}>
              <span class="avatar alt">1:1</span>
              <span class="list-text">
                <b>${t('home.outside')}</b>
                <small>${t('home.outsideHint')}${netByGroup.direct !== undefined ? html` · <${NetLine} net=${netByGroup.direct} />` : null}</small>
              </span>
              <span class="chev">›</span>
            </button>
          </div>

          ${mine.byPerson.length ? html`
            <h2 class="section-title">${t('home.whoPays')}</h2>
            <ul class="lines card">
              ${mine.byPerson.map((p) => html`
                <li key=${p.user.id}>
                  <span>${Number(p.net) < 0 ? t('home.payThem', { name: p.user.displayName }) : t('home.theyPay', { name: p.user.displayName })}</span>
                  <b class=${Number(p.net) < 0 ? 'neg' : 'pos'}>${fmt(Math.abs(Number(p.net)))}</b>
                </li>`)}
            </ul>` : null}`;
      }}<//>
    <//>`;
}

export function Pending() {
  const { user } = useStore(session);
  const load = useLoad(() => get('/api/me/pending'));
  const [busy, setBusy] = useState(false);
  const acceptAll = async () => {
    setBusy(true);
    try {
      await post('/api/me/pending/accept-all');
      await load.reload();
    } catch (e) {
      toast(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  return html`
    <${Page} title=${t('pending.title')} backTo="/">
      <${Loaded} load=${load}>${(pending) => {
        const total = pending.debts.length + pending.payments.length;
        if (!total) return html`<p class="empty">✓ ${t('pending.none')}</p>`;
        return html`
          ${total > 1 ? html`<${Button} kind="ok" busy=${busy} onClick=${acceptAll}>✓ ${t('action.acceptAll')} (${total})<//>` : null}
          ${pending.debts.length ? html`<h2 class="section-title">${t('pending.debts')}</h2>` : null}
          <div class="feed">
            ${pending.debts.map((d) => html`<${DebtItem} key=${d.id} debt=${d} me=${user} onChanged=${load.reload} />`)}
          </div>
          ${pending.payments.length ? html`<h2 class="section-title">${t('pending.payments')}</h2>` : null}
          <div class="feed">
            ${pending.payments.map((p) => html`<${PaymentItem} key=${p.id} payment=${p} me=${user} onChanged=${load.reload} />`)}
          </div>`;
      }}<//>
    <//>`;
}

export function Me() {
  const { user, token } = useStore(session);
  useStore(language);
  const [name, setName] = useState(user.displayName);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState('');

  const saveName = async (event) => {
    event.preventDefault();
    setBusy('name');
    setError(null);
    try {
      setSession(token, await patch('/api/me', { displayName: name }));
      toast(t('me.saved'));
    } catch (e) {
      setError(e);
    } finally {
      setBusy('');
    }
  };
  const savePassword = async (event) => {
    event.preventDefault();
    setBusy('password');
    setError(null);
    try {
      await post('/api/me/password', { currentPassword: current, newPassword: next });
      setCurrent('');
      setNext('');
      toast(t('me.passwordChanged'));
    } catch (e) {
      setError(e);
    } finally {
      setBusy('');
    }
  };

  return html`
    <${Page} title=${t('me.title')}>
      <section class="card form">
        <span class="field-label">${t('me.language')}</span>
        <${LanguageSwitch} />
      </section>
      <form class="card form" onSubmit=${saveName}>
        <${Field} label=${t('me.name')}>
          <${TextInput} value=${name} onInput=${setName} required maxlength="80" />
        <//>
        <p class="muted">${user.email}</p>
        <${Button} kind="secondary" type="submit" busy=${busy === 'name'}>${t('action.save')}<//>
      </form>
      <form class="card form" onSubmit=${savePassword}>
        <h2>${t('me.changePassword')}</h2>
        <${Field} label=${t('me.currentPassword')}>
          <${TextInput} value=${current} onInput=${setCurrent} type="password" autocomplete="current-password" required />
        <//>
        <${Field} label=${t('me.newPassword')} hint=${t('auth.passwordHint')}>
          <${TextInput} value=${next} onInput=${setNext} type="password" autocomplete="new-password" required minlength="8" />
        <//>
        <${Button} kind="secondary" type="submit" busy=${busy === 'password'}>${t('me.changePassword')}<//>
      </form>
      <${ErrorBox} error=${error} />
      <${Button} kind="ghost" onClick=${() => { logout(); replace('/login'); }}>${t('auth.logout')}<//>
      <a class="link center" href="/swagger-ui.html" target="_blank" rel="noopener">${t('me.apiDocs')}</a>
    <//>`;
}

export function Join({ code }) {
  const { user } = useStore(session);
  const load = useLoad(() => get(`/api/invites/${encodeURIComponent(code)}`), [code]);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);

  const join = async (placeholderId) => {
    setBusy(placeholderId ?? 'new');
    setError(null);
    try {
      const group = await post(`/api/invites/${encodeURIComponent(code)}/join`, placeholderId ? { placeholderId } : {});
      rememberInvite(null);
      replace(`/g/${group.id}`);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  };

  if (load.error && !load.data) {
    rememberInvite(null);
    return html`
      <${Page} title=${t('join.title')} backTo="/">
        <p class="empty">${load.error.code === 'invite_not_found' ? t('join.invalid') : errorText(load.error)}</p>
      <//>`;
  }
  return html`
    <${Page} title=${t('join.title')} backTo="/">
      <${Loaded} load=${load}>${(invite) => html`
        <section class="hero zero">
          <p class="hero-label">${t('join.invitedTo')}</p>
          <p class="hero-amount">${invite.groupName}</p>
          <p class="hero-sub">${t('home.members', { n: invite.memberCount })}</p>
        </section>
        ${invite.alreadyMember ? html`
          <p class="notice">${t('join.already')}</p>
          <${Button} onClick=${() => { rememberInvite(null); replace(`/g/${invite.groupId}`); }}>${t('join.open')}<//>` : null}
        ${invite.placeholders.length ? html`
          <h2 class="section-title">${invite.alreadyMember ? t('join.orClaim') : t('join.whoAreYou')}</h2>
          <div class="stack">
            ${invite.placeholders.map((p) => html`
              <${Button} key=${p.id} kind="secondary" busy=${busy === p.id} disabled=${busy !== null}
                         onClick=${() => join(p.id)}>${t('join.iAm', { name: p.displayName })}<//>`)}
          </div>` : null}
        ${!invite.alreadyMember ? html`
          ${invite.placeholders.length ? html`<p class="muted center">${t('join.someoneNew')}</p>` : null}
          <${Button} kind=${invite.placeholders.length ? 'ghost' : 'primary'} busy=${busy === 'new'} disabled=${busy !== null}
                     onClick=${() => join(null)}>${t('join.joinAs', { name: user.displayName })}<//>` : null}
        <${ErrorBox} error=${error} />
      `}<//>
    <//>`;
}
