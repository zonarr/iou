// Entry point: decides which screen to show for the current address and login state.
import { html, render, useEffect, useState, useStore, cls } from './lib.js';
import { t, language } from './i18n.js';
import { session, network, get, setSession, logout } from './api.js';
import { route, go, replace } from './router.js';
import { Toasts, Loading, ErrorBox } from './ui.js';
import { AuthScreen, rememberInvite } from './views-auth.js';
import { Home, Pending, Me, Join } from './views-home.js';
import { Group } from './views-group.js';
import { ExpenseForm, IouForm, PaymentForm } from './views-forms.js';
import { Direct, DirectNew } from './views-direct.js';

function WakeBanner() {
  const { waking } = useStore(network);
  return waking ? html`<div class="waking" role="status"><span class="spinner"></span>${t('app.waking')}</div>` : null;
}

function TabBar({ current }) {
  const tabs = [
    { path: '/', label: t('nav.home'), icon: '⌂' },
    { path: '/me', label: t('nav.me'), icon: '☺' },
  ];
  return html`
    <nav class="tabbar">
      ${tabs.map((tab) => html`
        <button key=${tab.path} class=${cls(current === tab.path && 'on')} onClick=${() => replace(tab.path)}>
          <span aria-hidden="true">${tab.icon}</span>${tab.label}
        </button>`)}
    </nav>`;
}

/** Picks the screen for a logged-in user. Returns [screen, showTabBar]. */
function screenFor({ parts, query }) {
  const [a, b, c, d] = parts;
  const id = (value) => (/^\d+$/.test(value || '') ? Number(value) : null);
  if (!a) return [html`<${Home} />`, true];
  if (a === 'me') return [html`<${Me} />`, true];
  if (a === 'pending') return [html`<${Pending} />`];
  if (a === 'join' && b) return [html`<${Join} code=${b} />`];
  if (a === 'g' && id(b)) {
    const groupId = id(b);
    if (c === 'expense') return [html`<${ExpenseForm} key=${`ge${d}`} groupId=${groupId} expenseId=${id(d)} query=${query} />`];
    if (c === 'iou') return [html`<${IouForm} key=${`gi${d}`} groupId=${groupId} debtId=${id(d)} query=${query} />`];
    if (c === 'pay') return [html`<${PaymentForm} key=${`gp${d}`} groupId=${groupId} paymentId=${id(d)} query=${query} />`];
    return [html`<${Group} key=${`g${groupId}`} id=${groupId} tab=${c || 'feed'} />`];
  }
  if (a === 'direct') {
    if (b === 'new') return [html`<${DirectNew} />`];
    if (b === 'expense') return [html`<${ExpenseForm} key=${`de${c}`} groupId=${null} expenseId=${id(c)} query=${query} />`];
    if (b === 'iou') return [html`<${IouForm} key=${`di${c}`} groupId=${null} debtId=${id(c)} query=${query} />`];
    if (b === 'pay') return [html`<${PaymentForm} key=${`dp${c}`} groupId=${null} paymentId=${id(c)} query=${query} />`];
    return [html`<${Direct} />`];
  }
  return [html`<${Home} />`, true];
}

function App() {
  const { token, user } = useStore(session);
  const current = useStore(route);
  useStore(language);
  const [error, setError] = useState(null);

  // After a reload we only have the token: fetch who it belongs to.
  useEffect(() => {
    if (token && !user) {
      setError(null);
      get('/api/me').then((me) => setSession(token, me), (e) => { if (session.get().token) setError(e); });
    }
  }, [token, user]);

  const [first, second] = current.parts;
  if (!token) {
    if (first === 'join' && second) rememberInvite(second);
    return html`<${WakeBanner} /><${AuthScreen} key=${first} mode=${first === 'register' ? 'register' : 'login'} /><${Toasts} />`;
  }
  if (!user) {
    return html`
      <${WakeBanner} />
      <main class="page">
        ${error ? html`<${ErrorBox} error=${error} onRetry=${() => setSession(token, null)} />
          <button class="link center" onClick=${logout}>${t('auth.logout')}</button>` : html`<${Loading} />`}
      </main>`;
  }
  if (first === 'login' || first === 'register') {
    replace('/');
    return null;
  }
  const [screen, tabs] = screenFor(current);
  return html`
    <${WakeBanner} />
    <div class=${cls('shell', tabs && 'with-tabs')}>${screen}</div>
    ${tabs ? html`<${TabBar} current=${current.path} />` : null}
    <${Toasts} />`;
}

render(html`<${App} />`, document.getElementById('app'));

if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('/sw.js').catch(() => { /* the app works without it */ });
}
