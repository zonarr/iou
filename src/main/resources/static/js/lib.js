// Re-exports the UI library and adds the few helpers every screen uses.
export * from './vendor/preact-htm.js';
import { useState, useEffect, useRef, useCallback } from './vendor/preact-htm.js';

/** A tiny observable value: components re-render when it changes. */
export function createStore(initial) {
  let state = initial;
  const listeners = new Set();
  return {
    get: () => state,
    set(patch) {
      state = { ...state, ...patch };
      listeners.forEach((listener) => listener(state));
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export function useStore(store) {
  const [state, setState] = useState(store.get());
  useEffect(() => store.subscribe(setState), [store]);
  return state;
}

/**
 * Loads data when the screen opens (and again when deps change).
 * Returns { data, error, loading, reload }. Old data stays visible while reloading.
 */
export function useLoad(loader, deps = []) {
  const [state, setState] = useState({ loading: true, data: null, error: null });
  const sequence = useRef(0);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  const reload = useCallback(() => {
    const mine = ++sequence.current;
    setState((s) => ({ ...s, loading: true, error: null }));
    return loaderRef.current().then(
      (data) => { if (sequence.current === mine) setState({ loading: false, data, error: null }); },
      (error) => { if (sequence.current === mine) setState((s) => ({ loading: false, data: s.data, error })); },
    );
  }, []);
  useEffect(() => { reload(); }, deps);
  return { ...state, reload };
}

/** Joins class names, skipping falsy ones: cls('chip', on && 'on'). */
export function cls(...names) {
  return names.filter(Boolean).join(' ');
}
