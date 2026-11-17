// One-on-one debts, outside any group.
import { html, useState, useLoad, useStore } from './lib.js';
import { t } from './i18n.js';
import { get, session } from './api.js';
import { go, replace, query } from './router.js';
import { Page, Loaded, Button, Field, Section, TextInput, Chips, ErrorBox } from './ui.js';
import { Feed } from './feed.js';

async function loadDirect() {
  const [expenses, debts, payments] = await Promise.all([get('/api/expenses'), get('/api/debts'), get('/api/payments')]);
  return { expenses, debts, payments };
}

/** Everybody I already have one-on-one entries with. */
function knownPeople({ expenses, debts, payments }, me) {
  const people = new Map();
  const add = (u) => { if (u && u.id !== me.id) people.set(u.id, u); };
  debts.forEach((d) => { add(d.debtor); add(d.creditor); });
  payments.forEach((p) => { add(p.from); add(p.to); });
  expenses.forEach((e) => { e.payers.forEach((p) => add(p.user)); e.shares.forEach((s) => add(s.user)); });
  return [...people.values()];
}

export function Direct() {
  const { user } = useStore(session);
  const load = useLoad(loadDirect);
  return html`
    <${Page} title=${t('direct.title')} backTo="/">
      <p class="muted">${t('direct.hint')}</p>
      <${Loaded} load=${load}>${(data) => html`
        <${Feed} ...${data} me=${user} group=${null} onChanged=${load.reload} empty=${t('direct.empty')}
          paths=${{
            expense: (e) => `/direct/expense/${e.id}`,
            debt: (d) => `/direct/iou/${d.id}`,
            payment: (p) => `/direct/pay/${p.id}`,
          }} />`}<//>
      <div class="actions-bar">
        <${Button} onClick=${() => go('/direct/new')}>＋ ${t('direct.add')}<//>
      </div>
    <//>`;
}

const KINDS = ['theyOwe', 'iOwe', 'splitIPaid', 'splitTheyPaid', 'iPaid', 'theyPaid'];

/** Step 1: who is it with. Step 2: what happened. Then the matching form opens, pre-filled. */
export function DirectNew() {
  const { user } = useStore(session);
  const load = useLoad(loadDirect);
  const [email, setEmail] = useState('');
  const [person, setPerson] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const find = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const found = await get(`/api/users/lookup${query({ email: email.trim() })}`);
      if (found.id === user.id) throw { code: 'same_person', message: 'same person' };
      setPerson(found);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const open = (kind) => {
    const who = { with: person.id, name: person.displayName };
    const target = {
      theyOwe: `/direct/iou${query({ ...who, debtor: person.id })}`,
      iOwe: `/direct/iou${query({ ...who, debtor: user.id })}`,
      splitIPaid: `/direct/expense${query({ ...who, payer: user.id })}`,
      splitTheyPaid: `/direct/expense${query({ ...who, payer: person.id })}`,
      iPaid: `/direct/pay${query({ ...who, from: user.id, to: person.id })}`,
      theyPaid: `/direct/pay${query({ ...who, from: person.id, to: user.id })}`,
    }[kind];
    replace(target);   // the form takes the picker's place, so saving returns to the list
  };

  const known = load.data ? knownPeople(load.data, user) : [];
  return html`
    <${Page} title=${t('direct.add')} backTo="/direct">
      ${known.length ? html`
        <${Section} label=${t('direct.recent')}>
          <${Chips} options=${known.map((p) => ({ value: p.id, label: p.displayName }))} value=${person?.id}
                    onChange=${(id) => setPerson(known.find((p) => p.id === id))} />
        <//>` : null}
      <form class="card form" onSubmit=${find}>
        <${Field} label=${t('direct.email')} hint=${t('direct.emailHint')}>
          <${TextInput} value=${email} onInput=${setEmail} type="email" inputmode="email" autocapitalize="none" required />
        <//>
        <${Button} kind="secondary" type="submit" busy=${busy}>${t('direct.find')}<//>
        <${ErrorBox} error=${error} />
      </form>
      ${person ? html`
        <p class="notice">✓ ${t('direct.found', { name: person.displayName })}</p>
        <${Section} label=${t('direct.kind')}>
          <div class="stack">
            ${KINDS.map((kind) => html`
              <${Button} key=${kind} kind="secondary" onClick=${() => open(kind)}>${t(`direct.kind.${kind}`)}<//>`)}
          </div>
        <//>` : null}
    <//>`;
}
