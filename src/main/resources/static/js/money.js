// Money helpers for display and for the live "= ฿..." preview.
// The server is the source of truth: it recalculates everything with BigDecimal.

export const BASE = 'THB';

const SYMBOLS = { THB: '฿', EUR: '€', CHF: 'CHF ', USD: '$', GBP: '£', JPY: '¥' };

/** 1234.5, 'EUR' -> "€1,234.50". */
export function fmt(amount, currency = BASE) {
  if (amount === null || amount === undefined || amount === '') return '';
  const n = Number(amount);
  const text = Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const symbol = SYMBOLS[currency] ?? `${currency} `;
  return `${n < 0 ? '−' : ''}${symbol}${text}`;
}

/** Cleans what was typed: "1 234,50" -> "1234.50". Returns '' if it is not a number. */
export function cleanNumber(text) {
  let s = String(text ?? '').trim().replace(/\s/g, '');
  if (s.includes(',') && !s.includes('.')) s = s.replace(',', '.');
  s = s.replace(/,/g, '');
  return /^\d*\.?\d+$/.test(s) || /^\d+\.$/.test(s) ? s.replace(/\.$/, '') : '';
}

function toScaled(text, scale) {
  const s = cleanNumber(text);
  if (!s) return null;
  const [whole, frac = ''] = s.split('.');
  if (frac.length > scale) return null;
  return BigInt(whole + frac.padEnd(scale, '0'));
}

/** True if the text is a positive amount with at most 2 decimals. */
export function validAmount(text) {
  const v = toScaled(text, 2);
  return v !== null && v > 0n;
}

export function validRate(text) {
  const v = toScaled(text, 8);
  return v !== null && v > 0n;
}

/**
 * amount x rate, rounded half-up to 2 decimals: the same rule the server uses.
 * Exact integer maths (BigInt), no floating point. Returns e.g. "1283.21", or null.
 */
export function toThb(amountText, rateText) {
  const amount = toScaled(amountText, 2);
  const rate = toScaled(rateText, 8);
  if (amount === null || rate === null) return null;
  const product = amount * rate;            // scale 10
  const divisor = 100000000n;               // back to scale 2
  const rounded = (product + divisor / 2n) / divisor;
  const digits = rounded.toString().padStart(3, '0');
  return `${digits.slice(0, -2)}.${digits.slice(-2)}`;
}

/** THB amount / rate, rounded to 2 decimals: used to suggest "pay this much in EUR". */
export function fromThb(thbText, rateText) {
  const thb = toScaled(thbText, 2);
  const rate = toScaled(rateText, 8);
  if (thb === null || rate === null || rate === 0n) return null;
  const scaled = thb * 100000000n;          // amount(2dp) = thb(2dp) * 1e8 / rate(8dp)
  const rounded = (scaled + rate / 2n) / rate;
  const digits = rounded.toString().padStart(3, '0');
  return `${digits.slice(0, -2)}.${digits.slice(-2)}`;
}

/** Sum of amount strings with 2 decimals, as a string. */
export function sum(texts) {
  let total = 0n;
  for (const text of texts) total += toScaled(text, 2) ?? 0n;
  const digits = total.toString().padStart(3, '0');
  return `${digits.slice(0, -2)}.${digits.slice(-2)}`;
}

/** "12.34" -> 1234n (null if it is not a number with at most `scale` decimals). */
export function scaled(text, scale = 2) {
  return toScaled(text, scale);
}

/** 1234n -> "12.34". Works for negative values too. */
export function unscaled(value, scale = 2) {
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(scale + 1, '0');
  const text = `${digits.slice(0, -scale)}.${digits.slice(-scale)}`;
  return negative ? `-${text}` : text;
}
