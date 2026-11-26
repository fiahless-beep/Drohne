// Skytac API: hält den Claude-Schlüssel, kennt den Nutzer, zählt Credits. Mehr nicht.
// Die App schickt dieselbe Anfrage wie bisher an /v1/messages, nur mit ihrem Anmelde-Token statt eines Claude-Schlüssels.
// Fliegen, Wächter und Sticks bleiben auf dem Handy; dieser Server sieht nie einen Flugbefehl.
import http from 'node:http';
import { prepare, makeLimiter, Refusal, KINDS } from './policy.mjs';

const env = process.env;
const need = k => { if (!env[k]) throw new Error(`Umgebungsvariable fehlt: ${k}`); return env[k]; };

export function makeApp(cfg, fetchImpl = fetch) {
  const allow = makeLimiter(Number(cfg.RATE_PER_MIN || 20));
  const tokens = new Map(); // Token → { user, until }

  const sb = (path, init = {}) => fetchImpl(`${cfg.SUPABASE_URL}${path}`, {
    ...init,
    headers: { apikey: cfg.SUPABASE_SERVICE_KEY, authorization: `Bearer ${cfg.SUPABASE_SERVICE_KEY}`, 'content-type': 'application/json', ...(init.headers || {}) },
  });
  const rpc = async (fn, args) => {
    const r = await sb(`/rest/v1/rpc/${fn}`, { method: 'POST', body: JSON.stringify(args) });
    const text = await r.text();
    let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* kein JSON */ }
    return { ok: r.ok, status: r.status, json };
  };

  async function userOf(req) {
    const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
    if (!m) throw new Refusal(401, 'auth', 'Sign in first.');
    const token = m[1];
    const hit = tokens.get(token);
    if (hit && hit.until > Date.now()) return hit.user;
    const r = await fetchImpl(`${cfg.SUPABASE_URL}/auth/v1/user`, { headers: { apikey: cfg.SUPABASE_ANON_KEY, authorization: `Bearer ${token}` } });
    if (!r.ok) throw new Refusal(401, 'auth', 'Sign-in expired. Sign in again.');
    const user = (await r.json())?.id;
    if (!user) throw new Refusal(401, 'auth', 'Sign-in expired. Sign in again.');
    if (tokens.size > 5000) tokens.clear();
    tokens.set(token, { user, until: Date.now() + 5 * 60_000 });
    return user;
  }

  async function readBody(req, max) {
    const chunks = []; let n = 0;
    for await (const c of req) { n += c.length; if (n > max) throw new Refusal(413, 'too_large', 'Request too large.'); chunks.push(c); }
    return Buffer.concat(chunks);
  }

  const send = (res, status, obj, headers = {}) => {
    const body = JSON.stringify(obj);
    res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), ...headers });
    res.end(body);
  };

  async function messages(req, res) {
    const user = await userOf(req);
    if (!allow(user)) throw new Refusal(429, 'rate', 'Too many requests. Try again in a moment.');
    const kindName = String(req.headers['x-skytac-kind'] || '');
    const maxBytes = Object.hasOwn(KINDS, kindName) ? KINDS[kindName].maxBodyBytes : 200_000;
    const raw = await readBody(req, maxBytes);
    let parsed; try { parsed = JSON.parse(raw.toString('utf8')); } catch { throw new Refusal(400, 'json', 'Body is not JSON.'); }
    const { cost, body } = prepare(kindName, parsed, raw.length, cfg);

    // Erst abbuchen, dann fragen: so kann niemand mit vielen gleichzeitigen Anfragen über sein Guthaben kommen.
    const spent = await rpc('skytac_spend', { p_user: user, p_cost: cost, p_kind: kindName, p_daily_cap: Number(cfg.DAILY_CREDIT_CAP || 20000) });
    if (!spent.ok) {
      const msg = spent.json?.message || '';
      if (msg.includes('insufficient_credits')) throw new Refusal(402, 'credits', 'Not enough credits for this request.');
      if (msg.includes('daily_cap')) throw new Refusal(503, 'cap', 'Skytac is at its limit for today. Try again tomorrow.');
      throw new Refusal(502, 'ledger', 'Credits could not be checked. Nothing was charged.');
    }
    const refund = () => rpc('skytac_refund', { p_user: user, p_cost: cost, p_kind: kindName }).catch(() => {});

    let up;
    try {
      up = await fetchImpl('https://api.anthropic.com/v1/messages', {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-api-key': cfg.ANTHROPIC_API_KEY, 'anthropic-version': cfg.ANTHROPIC_VERSION || '2026-10-21',
          ...(body.fallbacks ? { 'anthropic-beta': 'server-side-fallback-2026-07-01' } : {}) },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(120_000),
      });
    } catch {
      await refund();
      throw new Refusal(504, 'upstream', 'No answer from Claude. Nothing was charged.');
    }
    const text = await up.text();
    if (!up.ok) {
      await refund();
      // Fehlertexte von Anthropic nennen keine Schlüssel; trotzdem nur Status und Meldung durchreichen.
      let message = 'Claude error.'; try { message = JSON.parse(text)?.error?.message || message; } catch { /* egal */ }
      return send(res, up.status === 429 ? 429 : 502, { error: { type: 'upstream', message, upstream_status: up.status } });
    }
    const left = typeof spent.json === 'number' ? spent.json : spent.json?.credits;
    res.writeHead(200, { 'content-type': 'application/json', 'x-skytac-credits': String(left ?? ''), 'x-skytac-cost': String(cost) });
    res.end(text);
  }

  async function me(req, res) {
    const user = await userOf(req);
    const r = await rpc('skytac_account', { p_user: user, p_start: Number(cfg.START_CREDITS || 40) });
    if (!r.ok) throw new Refusal(502, 'ledger', 'Account could not be read.');
    send(res, 200, r.json);
  }

  return async (req, res) => {
    try {
      const url = new URL(req.url, 'http://x');
      if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true });
      if (req.method === 'GET' && url.pathname === '/v1/me') return await me(req, res);
      if (req.method === 'POST' && url.pathname === '/v1/messages') return await messages(req, res);
      send(res, 404, { error: { type: 'not_found', message: 'Not found.' } });
    } catch (e) {
      if (e instanceof Refusal) return send(res, e.status, { error: { type: e.code, message: e.message } });
      console.error('Fehler', e?.message);
      send(res, 500, { error: { type: 'server', message: 'Server error. Nothing was charged.' } });
    }
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const cfg = { ...env, SUPABASE_URL: need('SUPABASE_URL').replace(/\/$/, ''), SUPABASE_ANON_KEY: need('SUPABASE_ANON_KEY'), SUPABASE_SERVICE_KEY: need('SUPABASE_SERVICE_KEY'), ANTHROPIC_API_KEY: need('ANTHROPIC_API_KEY') };
  const port = Number(env.PORT || 8080);
  http.createServer(makeApp(cfg)).listen(port, '0.0.0.0', () => console.log(`skytac-api hört auf ${port}`));
}
