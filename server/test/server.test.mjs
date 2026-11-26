import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { makeApp } from '../src/server.mjs';

const cfg = { SUPABASE_URL: 'https://sb.test', SUPABASE_ANON_KEY: 'anon', SUPABASE_SERVICE_KEY: 'service', ANTHROPIC_API_KEY: 'sk-test' };

/** Attrappe für Supabase und Anthropic; merkt sich jeden Aufruf. */
function world({ credits = 5, claude = { status: 200, body: { content: [{ type: 'text', text: 'ok' }] } } } = {}) {
  const calls = []; const state = { credits };
  const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json' } });
  const f = async (url, init = {}) => {
    const u = String(url); calls.push({ u, init });
    if (u.endsWith('/auth/v1/user')) return init.headers.authorization === 'Bearer good' ? json(200, { id: 'user-1' }) : json(401, {});
    if (u.endsWith('/auth/v1/otp')) return json(200, {});
    if (u.endsWith('/auth/v1/verify')) return JSON.parse(init.body).token === '123456' ? json(200, { access_token: 'good', refresh_token: 'r1', expires_in: 3600, user: { email: 'a@b.co' } }) : json(403, {});
    if (u.includes('/auth/v1/token')) return JSON.parse(init.body).refresh_token === 'r1' ? json(200, { access_token: 'good', refresh_token: 'r2', expires_in: 3600, user: { email: 'a@b.co' } }) : json(400, {});
    if (u.endsWith('/rpc/skytac_spend')) {
      const a = JSON.parse(init.body);
      if (state.credits < a.p_cost) return json(400, { message: 'insufficient_credits' });
      state.credits -= a.p_cost; return json(200, state.credits);
    }
    if (u.endsWith('/rpc/skytac_refund')) { state.credits += JSON.parse(init.body).p_cost; return json(200, state.credits); }
    if (u.endsWith('/rpc/skytac_account')) return json(200, { credits: state.credits, plan: 'free' });
    if (u === 'https://api.anthropic.com/v1/messages') { if (claude.throw) throw new Error('netz'); return json(claude.status, claude.body); }
    throw new Error('unerwartet ' + u);
  };
  return { f, calls, state };
}

async function call(app, path, { method = 'GET', headers = {}, body } = {}) {
  const srv = http.createServer(app); await new Promise(r => srv.listen(0, r));
  try {
    const r = await fetch(`http://127.0.0.1:${srv.address().port}${path}`, { method, headers, body });
    return { status: r.status, headers: r.headers, json: await r.json() };
  } finally { srv.close(); }
}
const ask = (app, token = 'good', kind = 'ask', body = { messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }] }) =>
  call(app, '/v1/messages', { method: 'POST', headers: { authorization: `Bearer ${token}`, 'x-skytac-kind': kind, 'content-type': 'application/json' }, body: JSON.stringify(body) });

test('ohne Anmeldung nichts', async () => {
  const w = world();
  assert.equal((await call(makeApp(cfg, w.f), '/v1/messages', { method: 'POST', body: '{}' })).status, 401);
  assert.equal((await ask(makeApp(cfg, w.f), 'bad')).status, 401);
  assert.ok(!w.calls.some(c => c.u.includes('anthropic')));
});

test('Anfrage geht mit dem Server-Schlüssel durch und kostet Credits', async () => {
  const w = world({ credits: 5 });
  const r = await ask(makeApp(cfg, w.f));
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('x-skytac-credits'), '4');
  assert.equal(w.state.credits, 4);
  const up = w.calls.find(c => c.u.includes('anthropic'));
  assert.equal(up.init.headers['x-api-key'], 'sk-test');
  assert.equal(JSON.parse(up.init.body).model, 'claude-opus-5-5');
});

test('zu wenig Guthaben: 402, Claude wird nicht gefragt', async () => {
  const w = world({ credits: 5 });
  const r = await ask(makeApp(cfg, w.f), 'good', 'plan');
  assert.equal(r.status, 402);
  assert.ok(!w.calls.some(c => c.u.includes('anthropic')));
  assert.equal(w.state.credits, 5);
});

test('Fehler bei Claude: zurückgebucht, Schlüssel nie in der Antwort', async () => {
  for (const claude of [{ status: 500, body: { error: { message: 'kaputt' } } }, { throw: true }]) {
    const w = world({ credits: 5, claude });
    const r = await ask(makeApp(cfg, w.f));
    assert.ok(r.status >= 500);
    assert.equal(w.state.credits, 5);
    assert.ok(!JSON.stringify(r.json).includes('sk-test'));
  }
});

test('Konto und Gesundheit', async () => {
  const w = world({ credits: 40 });
  assert.deepEqual((await call(makeApp(cfg, w.f), '/v1/me', { headers: { authorization: 'Bearer good' } })).json, { credits: 40, plan: 'free' });
  assert.equal((await call(makeApp(cfg, w.f), '/health')).status, 200);
  assert.equal((await call(makeApp(cfg, w.f), '/nix')).status, 404);
});

const post = (app, path, obj) => call(app, path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(obj) });

test('Anmeldung: Code anfordern, einlösen, erneuern', async () => {
  const w = world(); const app = makeApp(cfg, w.f);
  assert.equal((await post(app, '/v1/auth/start', { email: 'kein-mail' })).status, 400);
  assert.equal((await post(app, '/v1/auth/start', { email: ' A@b.co ' })).status, 200);
  assert.equal(JSON.parse(w.calls.at(-1).init.body).email, 'a@b.co');
  assert.equal(w.calls.at(-1).init.headers.apikey, 'anon');
  assert.equal((await post(app, '/v1/auth/verify', { email: 'a@b.co', code: '000000' })).status, 401);
  const ok = await post(app, '/v1/auth/verify', { email: 'a@b.co', code: '123 456' });
  assert.equal(ok.status, 200); assert.equal(ok.json.refresh_token, 'r1');
  assert.equal((await post(app, '/v1/auth/refresh', { refresh_token: 'r1' })).json.refresh_token, 'r2');
});

test('Anmeldung: zu viele Versuche werden gebremst', async () => {
  const w = world(); const app = makeApp({ ...cfg, AUTH_PER_MIN: 2 }, w.f);
  await post(app, '/v1/auth/refresh', { refresh_token: 'x' }); await post(app, '/v1/auth/refresh', { refresh_token: 'x' });
  assert.equal((await post(app, '/v1/auth/refresh', { refresh_token: 'r1' })).status, 429);
});
