// Shared building blocks: page frame, buttons, chips, the amount/currency/rate fields.
import { html, useState, useEffect, createStore, useStore, cls } from './lib.js';
import { t, errorText, language } from './i18n.js';
import { fmt, toThb, validAmount, validRate, cleanNumber, BASE } from './money.js';
import { back } from './router.js';
import { get } from './api.js';

// ---- toasts ----
const toasts = createStore({ message: null });
let toastTimer = null;
export function toast(message) {
  toasts.set({ message });
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toasts.set({ message: null }), 2600);
}
export function Toasts() {
  const { message } = useStore(toasts);
  return message ? html`<div class="toast" role="status">${message}</div>` : null;
}

// ---- page frame ----
export function Page({ title, subtitle, backTo, action, children, wide }) {
  useStore(language);
  return html`
    <header class="bar">
      ${backTo !== undefined
        ? html`<button class="bar-btn" aria-label=${t('app.back')} onClick=${() => back(backTo)}>‹</button>`
        : html`<span class="bar-logo">iou</span>`}
      <div class="bar-title">
        <h1>${title}</h1>
        ${subtitle ? html`<p>${subtitle}</p>` : null}
      </div>
      <div class="bar-action">${action}</div>
    </header>
    <main class=${cls('page', wide && 'wide')}>${children}</main>`;
}

export function Loading() {
  return html`<div class="loading" aria-busy="true"><span class="spinner"></span>${t('app.loading')}</div>`;
}

export function ErrorBox({ error, onRetry }) {
  if (!error) return null;
  return html`
    <div class="error" role="alert">
      <span>${errorText(error)}</span>
      ${onRetry ? html`<button class="link" onClick=${onRetry}>${t('app.retry')}</button>` : null}
    </div>`;
}

/** Shows a spinner on first load, an error with retry on failure, otherwise the content. */
export function Loaded({ load, children }) {
  if (load.error && !load.data) return html`<${ErrorBox} error=${load.error} onRetry=${load.reload} />`;
  if (!load.data) return html`<${Loading} />`;
  return html`${load.error ? html`<${ErrorBox} error=${load.error} onRetry=${load.reload} />` : null}${children(load.data)}`;
}

export function Button({ kind = 'primary', busy, disabled, onClick, type = 'button', small, children, ...rest }) {
  return html`
    <button type=${type} class=${cls('btn', kind, small && 'small')} disabled=${disabled || busy}
            onClick=${onClick} ...${rest}>
      ${busy ? html`<span class="spinner"></span>` : null}${children}
    </button>`;
}

export function Field({ label, hint, children }) {
  return html`
    <label class="field">
      <span class="field-label">${label}</span>
      ${children}
      ${hint ? html`<span class="field-hint">${hint}</span>` : null}
    </label>`;
}

/** A group of related controls with a heading (not a single input, so not a <label>). */
export function Section({ label, hint, action, children }) {
  return html`
    <section class="field">
      <div class="field-head"><span class="field-label">${label}</span>${action}</div>
      ${children}
      ${hint ? html`<span class="field-hint">${hint}</span>` : null}
    </section>`;
}

export function TextInput({ value, onInput, ...rest }) {
  return html`<input class="input" value=${value} onInput=${(e) => onInput(e.target.value)} ...${rest} />`;
}

/**
 * Big tappable choices. options: [{ value, label, sub }].
 * Single choice: value is one value. multi: value is an array.
 */
export function Chips({ options, value, onChange, multi, wrap = true }) {
  const selected = (v) => (multi ? value.includes(v) : value === v);
  const toggle = (v) => {
    if (!multi) return onChange(v);
    onChange(value.includes(v) ? value.filter((x) => x !== v) : [...value, v]);
  };
  return html`
    <div class=${cls('chips', !wrap && 'scroll')} role=${multi ? 'group' : 'radiogroup'}>
      ${options.map((o) => html`
        <button type="button" key=${o.value} class=${cls('chip', selected(o.value) && 'on')}
                role=${multi ? 'checkbox' : 'radio'} aria-checked=${selected(o.value)}
                onClick=${() => toggle(o.value)}>
          ${multi ? html`<span class="tick" aria-hidden="true">${selected(o.value) ? '✓' : ''}</span>` : null}
          <span>${o.label}</span>
          ${o.sub ? html`<small>${o.sub}</small>` : null}
        </button>`)}
    </div>`;
}

export function StatusChip({ status }) {
  if (!status || status === 'CONFIRMED') return null;
  return html`<span class=${cls('status', status.toLowerCase())}>${t(`status.${status}`)}</span>`;
}

/** Original amount big, THB underneath when the original is in another currency. */
export function Amount({ original, currency, thb, sign }) {
  return html`
    <span class=${cls('amount', sign)}>
      <b>${fmt(original, currency)}</b>
      ${currency !== BASE ? html`<small>${fmt(thb)}</small>` : null}
    </span>`;
}

// ---- amount + currency + rate ----
let currencyList = null;
function useCurrencies() {
  const [list, setList] = useState(currencyList);
  useEffect(() => {
    if (!currencyList) get('/api/currencies').then((c) => { currencyList = c; setList(c); }).catch(() => {});
  }, []);
  return list;
}

/**
 * value: { amount, currency, rate } (strings). rates: { EUR: 38.5, ... } remembered rates.
 * Choosing a currency fills in the remembered rate; the THB value is shown live.
 */
export function MoneyFields({ value, onChange, rates, autoFocus }) {
  const currencies = useCurrencies();
  const featured = currencies?.featured || ['THB', 'EUR', 'CHF'];
  const isOther = !featured.includes(value.currency);
  const [showOther, setShowOther] = useState(isOther);

  const pick = (currency) => {
    const remembered = rates && rates[currency] !== undefined ? String(rates[currency]) : '';
    onChange({ ...value, currency, rate: currency === BASE ? '' : remembered });
  };
  const foreign = value.currency !== BASE;
  const thb = foreign ? toThb(value.amount, value.rate) : null;

  return html`
    <${Field} label=${t('form.amount')}>
      <input class="input big" inputmode="decimal" autocomplete="off" placeholder="0.00"
             autofocus=${autoFocus} value=${value.amount}
             onInput=${(e) => onChange({ ...value, amount: e.target.value })} />
    <//>
    <${Section} label=${t('form.currency')}>
      <div class="chips">
        ${featured.map((code) => html`
          <button type="button" key=${code} class=${cls('chip', value.currency === code && 'on')}
                  role="radio" aria-checked=${value.currency === code}
                  onClick=${() => { setShowOther(false); pick(code); }}>${code}</button>`)}
        <button type="button" class=${cls('chip', (showOther || isOther) && 'on')}
                onClick=${() => setShowOther(true)}>${isOther ? value.currency : t('form.otherCurrency')}</button>
      </div>
      ${showOther || isOther ? html`
        <select class="input" value=${isOther ? value.currency : ''} onChange=${(e) => e.target.value && pick(e.target.value)}>
          <option value="">${t('form.otherCurrency')}</option>
          ${(currencies?.all || []).filter((c) => !featured.includes(c.code)).map((c) =>
            html`<option key=${c.code} value=${c.code}>${c.code} – ${c.name}</option>`)}
        </select>` : null}
    <//>
    ${foreign ? html`
      <${Section} label=${t('form.rate')} hint=${t('form.rateHint')}>
        <div class="rate-row">
          <span>${t('form.rateLine', { currency: value.currency })}</span>
          <input class="input" inputmode="decimal" autocomplete="off" placeholder="0.00"
                 aria-label=${t('form.rate')} value=${value.rate}
                 onInput=${(e) => onChange({ ...value, rate: e.target.value })} />
          <span>${t('form.rateUnit')}</span>
        </div>
        ${thb ? html`<div class="equals">${t('form.equals', { amount: fmt(thb) })}</div>` : null}
      <//>` : null}`;
}

/** Checks the money part of a form; returns an error code (for err.*) or null. */
export function moneyProblem(money) {
  if (!String(money.amount).trim()) return 'amount_required';
  if (!validAmount(money.amount)) {
    return /^\s*-|^0*\.?0*$/.test(String(money.amount).trim()) ? 'amount_not_positive' : 'amount_too_precise';
  }
  if (money.currency !== BASE) {
    if (!String(money.rate).trim()) return 'rate_required';
    if (!validRate(money.rate)) return 'rate_not_positive';
  }
  return null;
}

/** The money part of a request body. */
export function moneyBody(money) {
  return {
    amount: cleanNumber(money.amount),
    currency: money.currency,
    exchangeRate: money.currency === BASE ? null : cleanNumber(money.rate),
  };
}

export function emptyMoney() {
  return { amount: '', currency: BASE, rate: '' };
}

export function moneyFrom(item) {
  return {
    amount: Number(item.originalAmount).toFixed(2),
    currency: item.currency,
    rate: item.currency === BASE ? '' : String(item.exchangeRate),
  };
}

/** Local error for forms: { code } objects go through the same translation as API errors. */
export function localError(code) {
  return { code, message: code };
}
