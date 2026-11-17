// Log in / create account.
import { html, useState } from './lib.js';
import { t, language, setLanguage } from './i18n.js';
import { post, setSession } from './api.js';
import { replace } from './router.js';
import { Button, Field, TextInput, ErrorBox } from './ui.js';

export const INVITE_KEY = 'iou.invite';

export function pendingInvite() {
  try { return sessionStorage.getItem(INVITE_KEY); } catch { return null; }
}

export function rememberInvite(code) {
  try { code ? sessionStorage.setItem(INVITE_KEY, code) : sessionStorage.removeItem(INVITE_KEY); } catch { /* ignore */ }
}

export function LanguageSwitch() {
  const { lang } = language.get();
  return html`
    <div class="lang" role="group" aria-label="Language">
      <button class=${lang === 'th' ? 'on' : ''} onClick=${() => setLanguage('th')}>ไทย</button>
      <button class=${lang === 'en' ? 'on' : ''} onClick=${() => setLanguage('en')}>English</button>
    </div>`;
}

export function AuthScreen({ mode }) {
  const registering = mode === 'register';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const invite = pendingInvite();

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = registering ? { email, password, displayName: name } : { email, password };
      const result = await post(registering ? '/api/auth/register' : '/api/auth/login', body);
      setSession(result.token, result.user);
      replace(invite ? `/join/${invite}` : '/');
    } catch (e) {
      if (e.code === 'validation_failed' && e.fields) {
        const first = Object.keys(e.fields)[0];
        setError({ code: `field_${first}`, message: `${t(first === 'displayName' ? 'auth.name' : `auth.${first}`)}: ${e.fields[first]}` });
      } else {
        setError(e);
      }
    } finally {
      setBusy(false);
    }
  };

  return html`
    <main class="page auth">
      <${LanguageSwitch} />
      <div class="brand">
        <div class="logo">iou</div>
        <p>${t('app.tagline')}</p>
      </div>
      ${invite ? html`<p class="notice">${t('auth.invited')}</p>` : null}
      <form class="card form" onSubmit=${submit}>
        <h1>${registering ? t('auth.register') : t('auth.login')}</h1>
        ${registering ? html`
          <${Field} label=${t('auth.name')} hint=${t('auth.nameHint')}>
            <${TextInput} value=${name} onInput=${setName} autocomplete="name" required maxlength="80" />
          <//>` : null}
        <${Field} label=${t('auth.email')}>
          <${TextInput} value=${email} onInput=${setEmail} type="email" inputmode="email" autocomplete="email"
                        autocapitalize="none" required />
        <//>
        <${Field} label=${t('auth.password')} hint=${registering ? t('auth.passwordHint') : null}>
          <${TextInput} value=${password} onInput=${setPassword} type="password" required minlength=${registering ? 8 : 1}
                        autocomplete=${registering ? 'new-password' : 'current-password'} />
        <//>
        <${ErrorBox} error=${error} />
        <${Button} type="submit" busy=${busy}>${registering ? t('auth.register') : t('auth.login')}<//>
      </form>
      <button class="link center" onClick=${() => replace(registering ? '/login' : '/register')}>
        ${registering ? t('auth.haveAccount') : t('auth.noAccount')}
      </button>
    </main>`;
}
