import test from 'node:test';
import assert from 'node:assert/strict';
import { prepare, makeLimiter, Refusal, KINDS } from '../src/policy.mjs';

const img = { type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: 'AAAA' } };
const msg = (...content) => [{ role: 'user', content }];
const refused = (fn, code) => assert.throws(fn, e => e instanceof Refusal && e.code === code);

test('Modell und Antwortlänge setzt der Server', () => {
  const { body, cost } = prepare('ask', { model: 'claude-teuer', max_tokens: 999999, messages: msg({ type: 'text', text: 'hi' }), secret: 1 }, 100);
  assert.equal(body.model, 'claude-opus-5-5');
  assert.equal(body.max_tokens, KINDS.ask.maxTokens);
  assert.equal(body.secret, undefined);
  assert.equal(cost, 1);
  assert.equal(prepare('ask', { messages: msg(img) }, 100, { MODEL_IMAGE: 'claude-sonnet-5-5' }).body.model, 'claude-sonnet-5-5');
});

test('Preise je Art', () => {
  assert.equal(prepare('spot', { messages: msg(...Array(40).fill(img)) }, 100).cost, 10);
  assert.equal(prepare('plan', { messages: msg({ type: 'text', text: 'x' }) }, 100).cost, 30);
  assert.equal(prepare('edit', { messages: msg({ type: 'text', text: 'x' }) }, 100).cost, 12);
  assert.equal(prepare('check', { messages: msg(img) }, 100).cost, 1);
});

test('Grenzen', () => {
  refused(() => prepare('nix', { messages: msg(img) }, 100), 'kind');
  refused(() => prepare('constructor', { messages: msg(img) }, 100), 'kind');
  refused(() => prepare('ask', { messages: msg(img, img) }, 100), 'images');
  refused(() => prepare('spot', { messages: msg(...Array(41).fill(img)) }, 100), 'images');
  refused(() => prepare('explain', { messages: msg(img) }, 100), 'images');
  refused(() => prepare('ask', { messages: msg(img) }, KINDS.ask.maxBodyBytes + 1), 'too_large');
  refused(() => prepare('ask', { messages: [] }, 100), 'messages');
  refused(() => prepare('ask', { messages: msg(img), stream: true }, 100), 'stream');
  refused(() => prepare('ask', { messages: msg({ type: 'image', source: { type: 'url', url: 'http://x' } }) }, 100), 'image_source');
  refused(() => prepare('ask', { messages: msg({ type: 'document' }) }, 100), 'content_type');
  refused(() => prepare('check', { messages: msg(img), tools: [{ name: 'a' }] }, 100), 'tools');
  refused(() => prepare('ask', { messages: msg(img), tools: [{ type: 'web_search_20260209', name: 'web_search' }] }, 100), 'tools');
  refused(() => prepare('ask', [], 100), 'body');
});

test('Bremse je Nutzer', () => {
  const ok = makeLimiter(2, 1000);
  assert.equal(ok('a', 0), true); assert.equal(ok('a', 1), true); assert.equal(ok('a', 2), false);
  assert.equal(ok('b', 2), true);
  assert.equal(ok('a', 1500), true);
});
