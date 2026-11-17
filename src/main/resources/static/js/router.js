// Hash-based routing (#/g/3/expense): works from any static host, no server rewrites needed.
import { createStore } from './lib.js';

function parse() {
  const raw = location.hash.replace(/^#/, '') || '/';
  const [path, queryString] = raw.split('?');
  return {
    path,
    parts: path.split('/').filter(Boolean).map(decodeURIComponent),
    query: Object.fromEntries(new URLSearchParams(queryString || '')),
  };
}

export const route = createStore(parse());
let depth = 0;

window.addEventListener('hashchange', () => {
  route.set(parse());
  window.scrollTo(0, 0);
});

export function go(path) {
  depth++;
  location.hash = `#${path}`;
}

/** Replaces the current screen (no new history entry), e.g. after saving a form. */
export function replace(path) {
  history.replaceState(null, '', `#${path}`);
  route.set(parse());
  window.scrollTo(0, 0);
}

/** Goes back if we came from inside the app, otherwise to the given fallback screen. */
export function back(fallback = '/') {
  if (depth > 0) {
    depth--;
    history.back();
  } else {
    replace(fallback);
  }
}

export function query(params) {
  const usable = Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== '');
  return usable.length ? `?${new URLSearchParams(usable)}` : '';
}
