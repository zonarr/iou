// History: who did what, newest first. Built on the server's audit log.
import { html, useState, useEffect } from './lib.js';
import { t, errorText, formatDateTime } from './i18n.js';
import { fmt, BASE } from './money.js';
import { get } from './api.js';
import { query } from './router.js';
import { Page, Button, Loading, ErrorBox } from './ui.js';

const ICONS = { EXPENSE: '🧾', DEBT: '🤝', PAYMENT: '💸', GROUP: '👥', MEMBER: '🙂' };

function money(state) {
  if (!state || state.originalAmount === undefined) return null;
  const original = fmt(state.originalAmount, state.currency);
  return state.currency === BASE ? original : `${original} (${fmt(state.thbAmount)})`;
}

/** Turns one audit entry into a sentence plus optional detail lines. */
function describe(entry, people) {
  const name = (id) => people[id] || '?';
  const state = entry.after || entry.before || {};
  const actor = entry.actor?.displayName || t('activity.someone');
  const vars = { actor };
  const details = [];

  if (entry.entityType === 'EXPENSE') {
    vars.what = state.description;
  } else if (entry.entityType === 'DEBT') {
    vars.debtor = name(state.debtorId);
    vars.creditor = name(state.creditorId);
    if (state.description) details.push(state.description);
  } else if (entry.entityType === 'PAYMENT') {
    vars.from = name(state.fromUserId);
    vars.to = name(state.toUserId);
    if (state.note) details.push(state.note);
  } else if (entry.entityType === 'MEMBER') {
    vars.name = state.name;
    vars.old = entry.before?.name;
  } else if (entry.entityType === 'GROUP' && entry.action === 'UPDATED' && entry.before && entry.after) {
    if (entry.before.autoConfirm !== entry.after.autoConfirm) {
      details.push(t(entry.after.autoConfirm ? 'activity.trustedOn' : 'activity.trustedOff'));
    }
    if (entry.before.name !== entry.after.name) details.push(t('activity.renamedGroup', { name: entry.after.name }));
  }

  const amount = money(state);
  if (amount) {
    const was = entry.action === 'UPDATED' && entry.before && entry.after
      && (entry.before.thbAmount !== entry.after.thbAmount || entry.before.currency !== entry.after.currency
        || entry.before.originalAmount !== entry.after.originalAmount)
      ? ` · ${t('activity.was', { amount: money(entry.before) })}` : '';
    details.unshift(`${amount}${was}`);
  }
  return { text: t(`activity.${entry.entityType}.${entry.action}`, vars), details };
}

/** Loads pages of history from `path` and lets the reader ask for older ones. */
function Timeline({ path, showGroup }) {
  const [entries, setEntries] = useState([]);
  const [people, setPeople] = useState({});
  const [next, setNext] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = async (before) => {
    setLoading(true);
    setError(null);
    try {
      const page = await get(`${path}${query({ before })}`);
      setEntries((old) => (before ? [...old, ...page.entries] : page.entries));
      setPeople((old) => ({ ...(before ? old : {}), ...page.people }));
      setNext(page.nextBefore);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(null); }, [path]);

  if (!entries.length && loading) return html`<${Loading} />`;
  if (!entries.length && !error) return html`<p class="empty">${t('activity.empty')}</p>`;
  return html`
    <ol class="timeline">
      ${entries.map((entry) => {
        const { text, details } = describe(entry, people);
        return html`
          <li key=${entry.id}>
            <span class="timeline-icon" aria-hidden="true">${ICONS[entry.entityType] || '•'}</span>
            <div class="timeline-body">
              <p>${text}</p>
              ${details.map((line) => html`<p class="muted" key=${line}>${line}</p>`)}
              <p class="timeline-when">${formatDateTime(entry.at)}${showGroup && entry.groupName ? ` · ${entry.groupName}` : ''}</p>
            </div>
          </li>`;
      })}
    </ol>
    <${ErrorBox} error=${error} onRetry=${() => load(next)} />
    ${next ? html`<${Button} kind="ghost" busy=${loading} onClick=${() => load(next)}>${t('activity.more')}<//>` : null}`;
}

export function GroupActivity({ groupId }) {
  return html`<${Timeline} path=${`/api/groups/${groupId}/activity`} />`;
}

export function Activity() {
  return html`
    <${Page} title=${t('activity.title')}>
      <${Timeline} path="/api/me/activity" showGroup />
    <//>`;
}
