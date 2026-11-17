// Talks to the backend: keeps the login token, turns error responses into ApiError,
// and copes with a server that is asleep (Render's free tier sleeps after 15 idle minutes).
import { createStore } from './lib.js';

const TOKEN_KEY = 'iou.token';
const store = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { value === null ? localStorage.removeItem(key) : localStorage.setItem(key, value); } catch { /* private mode */ } },
};

/** { token, user } - user is null until loaded. */
export const session = createStore({ token: store.get(TOKEN_KEY), user: null });
/** { waking } - true while we wait for the server to start. */
export const network = createStore({ waking: false });

export function setSession(token, user) {
  store.set(TOKEN_KEY, token);
  session.set({ token, user });
}

export function logout() {
  setSession(null, null);
}

export class ApiError extends Error {
  constructor(status, code, message, fields) {
    super(message);
    this.status = status;
    this.code = code;
    this.fields = fields || null;
  }
}

const AWAKE_FOR_MS = 10 * 60 * 1000;
const GIVE_UP_AFTER_MS = 4 * 60 * 1000;
let lastOk = 0;
let waking = null;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function healthy() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch('/api/health', { signal: controller.signal, cache: 'no-store' });
    if (!response.ok) return false;
    const body = await response.json();
    return body.status === 'UP';
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Makes sure the server is up before a request is sent. If we heard from it recently this
 * returns at once; otherwise it polls the health endpoint, showing the "waking up" banner.
 */
function ensureAwake() {
  if (Date.now() - lastOk < AWAKE_FOR_MS) return Promise.resolve();
  if (!waking) {
    waking = (async () => {
      const started = Date.now();
      const banner = setTimeout(() => network.set({ waking: true }), 2500);
      try {
        while (!(await healthy())) {
          if (Date.now() - started > GIVE_UP_AFTER_MS) {
            throw new ApiError(0, 'server_unreachable', 'The server cannot be reached');
          }
          await sleep(3000);
        }
        lastOk = Date.now();
      } finally {
        clearTimeout(banner);
        network.set({ waking: false });
        waking = null;
      }
    })();
  }
  return waking;
}

async function send(method, path, body) {
  const headers = {};
  const { token } = session.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  });
  if ([502, 503, 504].includes(response.status)) {
    throw new ApiError(response.status, 'server_unreachable', 'The server cannot be reached');
  }
  lastOk = Date.now();
  if (response.status === 204) return null;
  let data = null;
  try { data = await response.json(); } catch { /* empty or non-JSON body */ }
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/api/auth/')) logout();
    throw new ApiError(response.status, data?.error || 'error', data?.message || 'Something went wrong', data?.fields);
  }
  return data;
}

/**
 * api('GET', '/api/groups')  /  api('POST', '/api/groups', { name })
 *
 * Reads are retried once after waking the server. Writes are never retried automatically:
 * if the connection drops mid-request we cannot know whether it was saved, and a second
 * attempt could record the same expense twice.
 */
export async function api(method, path, body) {
  await ensureAwake();
  try {
    return await send(method, path, body);
  } catch (error) {
    const unreachable = !(error instanceof ApiError) || error.code === 'server_unreachable';
    if (!unreachable) throw error;
    lastOk = 0;
    if (method !== 'GET') {
      throw new ApiError(0, 'network_error', 'Connection lost. Check whether it was saved before trying again.');
    }
    await ensureAwake();
    return send(method, path, body);
  }
}

export const get = (path) => api('GET', path);
export const post = (path, body) => api('POST', path, body ?? {});
export const put = (path, body) => api('PUT', path, body);
export const patch = (path, body) => api('PATCH', path, body);
export const del = (path) => api('DELETE', path);
