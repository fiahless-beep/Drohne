// Die festen Regeln des Servers, ohne Netz und ohne Uhr, damit sie prüfbar sind.
// Jede Art von Anfrage hat einen Preis in Credits und harte Grenzen. Was die App schickt, wird nicht geglaubt:
// Modell, Antwortlänge und Bildzahl setzt bzw. begrenzt der Server.

/** Arten von Anfragen (Kopfzeile x-skytac-kind). Preise wie im Geschäftsmodell, Fassung 2. */
export const KINDS = {
  ask:     { cost: 1,  maxImages: 1,  maxBodyBytes: 3_000_000,  maxTokens: 4000, tools: true,  model: 'MODEL_IMAGE' },
  explain: { cost: 1,  maxImages: 0,  maxBodyBytes: 200_000,    maxTokens: 2000, tools: false, model: 'MODEL_IMAGE' },
  check:   { cost: 1,  maxImages: 1,  maxBodyBytes: 3_000_000,  maxTokens: 2000, tools: false, model: 'MODEL_IMAGE' },
  spot:    { cost: 10, maxImages: 40, maxBodyBytes: 24_000_000, maxTokens: 4000, tools: true,  model: 'MODEL_IMAGE' },
  edit:    { cost: 12, maxImages: 0,  maxBodyBytes: 400_000,    maxTokens: 8000, tools: true,  model: 'MODEL_PLAN' },
  plan:    { cost: 30, maxImages: 0,  maxBodyBytes: 400_000,    maxTokens: 16000, tools: true, model: 'MODEL_PLAN' },
};

export const DEFAULT_MODELS = { MODEL_IMAGE: 'claude-opus-5-5', MODEL_PLAN: 'claude-opus-5-5' };

/** Felder, die an Anthropic weitergehen dürfen. Alles andere fällt weg. */
const ALLOWED = ['system', 'messages', 'tools', 'tool_choice', 'output_config', 'fallbacks'];

export class Refusal extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}

function countImages(messages) {
  let n = 0;
  for (const m of messages) {
    if (!Array.isArray(m?.content)) continue;
    for (const b of m.content) {
      if (b?.type === 'image') {
        // Nur eingebettete Bilder: eine Adresse würde den Server fremde Seiten abrufen lassen.
        if (b.source?.type !== 'base64') throw new Refusal(400, 'image_source', 'Images must be sent inline.');
        n++;
      } else if (b?.type !== 'text' && b?.type !== 'tool_use' && b?.type !== 'tool_result') {
        throw new Refusal(400, 'content_type', `Content type not allowed: ${String(b?.type)}`);
      }
    }
  }
  return n;
}

/**
 * Prüft die Anfrage der App und baut daraus die Anfrage an Anthropic.
 * @returns {{cost:number, body:object}}
 */
export function prepare(kindName, body, bodyBytes, env = {}) {
  const kind = Object.hasOwn(KINDS, kindName) ? KINDS[kindName] : null;
  if (!kind) throw new Refusal(400, 'kind', 'Unknown request kind.');
  if (bodyBytes > kind.maxBodyBytes) throw new Refusal(413, 'too_large', 'Request too large for this kind.');
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Refusal(400, 'body', 'Body must be a JSON object.');
  if (!Array.isArray(body.messages) || body.messages.length === 0 || body.messages.length > 16) throw new Refusal(400, 'messages', 'messages missing or too many.');
  if (body.stream) throw new Refusal(400, 'stream', 'Streaming is not offered.');
  const images = countImages(body.messages);
  if (images > kind.maxImages) throw new Refusal(400, 'images', `At most ${kind.maxImages} image(s) for this kind.`);
  if (body.tools && !kind.tools) throw new Refusal(400, 'tools', 'Tools are not offered for this kind.');
  if (Array.isArray(body.tools)) {
    // Nur eigene Werkzeuge der App. Server-Werkzeuge (Websuche, Code) kosten extra und sind hier nicht vorgesehen.
    for (const t of body.tools) if (t?.type && t.type !== 'custom') throw new Refusal(400, 'tools', 'Only client tools are offered.');
    if (body.tools.length > 12) throw new Refusal(400, 'tools', 'Too many tools.');
  }
  const out = {};
  for (const k of ALLOWED) if (body[k] !== undefined) out[k] = body[k];
  out.model = env[kind.model] || DEFAULT_MODELS[kind.model];
  const wanted = Number.isInteger(body.max_tokens) && body.max_tokens > 0 ? body.max_tokens : kind.maxTokens;
  out.max_tokens = Math.min(wanted, kind.maxTokens);
  // 03 kostet je Standbild; alle anderen Arten haben einen festen Preis.
  const cost = kindName === 'check' ? Math.max(1, images) * kind.cost : kind.cost;
  return { cost, body: out };
}

/** Einfache Bremse je Nutzer: höchstens [limit] Anfragen in [windowMs]. Gibt true zurück, wenn die Anfrage durch darf. */
export function makeLimiter(limit = 20, windowMs = 60_000) {
  const seen = new Map();
  return (user, now = Date.now()) => {
    const list = (seen.get(user) || []).filter(t => now - t < windowMs);
    if (list.length >= limit) { seen.set(user, list); return false; }
    list.push(now); seen.set(user, list);
    if (seen.size > 5000) for (const [k, v] of seen) if (v.every(t => now - t >= windowMs)) seen.delete(k);
    return true;
  };
}
