function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
      'access-control-allow-headers': 'content-type,x-parent-key,x-parent-token,x-child-token',
      'cache-control': 'no-store'
    }
  });
}

function dashboard() {
  const html = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Bedtime Parent Control</title></head><body><h1>Bedtime Parent Control</h1><p>Use the Android Parent dashboard for secure pairing and controls.</p></body></html>`;
  return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } });
}

function randomToken(bytes = 32) {
  const data = new Uint8Array(bytes);
  crypto.getRandomValues(data);
  let binary = '';
  for (const b of data) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function randomPairCode() {
  const data = new Uint32Array(1);
  crypto.getRandomValues(data);
  return String(100000 + (data[0] % 900000));
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(value || '');
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('');
}

async function tokenMatches(value, expectedHash) {
  if (!expectedHash || !value) return false;
  return (await sha256(value)) === expectedHash;
}

export class BedtimeState {
  constructor(state, env) { this.state = state; this.env = env; }
  async fetch(request) {
    const url = new URL(request.url);
    const childId = url.searchParams.get('childId') || '';
    if (url.pathname === '/state') {
      if (request.method === 'GET') {
        const stored = await this.state.storage.get('state');
        const credentials = await this.state.storage.get('credentials');
        return json({ childId, active: stored?.active === true, allowPowerControls: stored?.allowPowerControls === true, updatedAt: stored?.updatedAt || null, paired: credentials?.paired === true });
      }
      if (request.method === 'POST') {
        const body = await request.json();
        const previous = await this.state.storage.get('state');
        const next = { active: body.active === true, allowPowerControls: body.allowPowerControls === true || (body.allowPowerControls === undefined && previous?.allowPowerControls === true), updatedAt: new Date().toISOString() };
        await this.state.storage.put('state', next);
        return json({ ok: true, childId, ...next });
      }
    }
    if (url.pathname === '/credentials') {
      if (request.method === 'GET') {
        const credentials = await this.state.storage.get('credentials');
        return json({ childId, credentials: credentials || null });
      }
      if (request.method === 'POST') {
        const body = await request.json();
        const current = await this.state.storage.get('credentials');
        const next = {
          childTokenHash: body.childTokenHash || current?.childTokenHash || null,
          parentTokenHash: body.parentTokenHash !== undefined ? body.parentTokenHash : (current?.parentTokenHash || null),
          paired: body.paired === true,
          pairedAt: body.paired === true ? (body.pairedAt || new Date().toISOString()) : (current?.pairedAt || null)
        };
        await this.state.storage.put('credentials', next);
        return json({ ok: true, childId });
      }
    }
    if (url.pathname === '/pair') {
      if (request.method === 'GET') {
        const pending = await this.state.storage.get('pair');
        if (!pending) return json({ error: 'pairing code not found' }, 404);
        return json(pending);
      }
      if (request.method === 'POST') {
        const body = await request.json();
        await this.state.storage.put('pair', body);
        return json({ ok: true });
      }
      if (request.method === 'DELETE') {
        await this.state.storage.delete('pair');
        return json({ ok: true });
      }
    }
    return json({ error: 'method not allowed' }, 405);
  }
}

async function childStub(env, childId) {
  const id = env.BEDTIME_STATE_DO.idFromName(childId);
  return env.BEDTIME_STATE_DO.get(id);
}

async function getCredentials(env, childId) {
  const stub = await childStub(env, childId);
  const response = await stub.fetch(new Request(`https://bedtime-state.internal/credentials?childId=${encodeURIComponent(childId)}`, { method: 'GET' }));
  const data = await response.json();
  return data.credentials || null;
}

async function authorizedForRead(request, credentials) {
  if (!credentials?.paired) return true;
  return await tokenMatches(request.headers.get('x-parent-token') || '', credentials.parentTokenHash) || await tokenMatches(request.headers.get('x-child-token') || '', credentials.childTokenHash);
}

async function authorizedParent(request, credentials, env) {
  if (credentials?.paired) return await tokenMatches(request.headers.get('x-parent-token') || '', credentials.parentTokenHash);
  if (env.PARENT_API_KEY) return (request.headers.get('x-parent-key') || '') === env.PARENT_API_KEY;
  return true;
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return json({ ok: true });
    const url = new URL(request.url);
    if (url.pathname === '/' && request.method === 'GET') return dashboard();
    if (url.pathname === '/health') return json({ ok: true, service: 'bedtime-parental-worker', storage: 'durable-object', pairing: 'token-v2-parent-generated' });

    if (url.pathname === '/api/pairing/start-parent' && request.method === 'POST') {
      let body;
      try { body = await request.json(); } catch { return json({ error: 'invalid json' }, 400); }
      const childId = String(body?.childId || '').trim();
      const recoveryPin = String(body?.recoveryPin || '').trim();
      if (!/^[A-Za-z0-9._-]{1,80}$/.test(childId)) return json({ error: 'invalid childId' }, 400);
      if (!/^\d{6}$/.test(recoveryPin)) return json({ error: 'recoveryPin must be 6 digits' }, 400);

      const parentToken = randomToken(32);
      const parentTokenHash = await sha256(parentToken);
      const child = await childStub(env, childId);
      await child.fetch(new Request(`https://bedtime-state.internal/credentials?childId=${encodeURIComponent(childId)}`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ parentTokenHash, paired: false })
      }));

      let pairCode = '';
      let pairStub;
      for (let attempt = 0; attempt < 8; attempt++) {
        const candidate = randomPairCode();
        const pairId = env.BEDTIME_STATE_DO.idFromName(`pair:${candidate}`);
        const candidateStub = env.BEDTIME_STATE_DO.get(pairId);
        const check = await candidateStub.fetch(new Request('https://bedtime-state.internal/pair', { method: 'GET' }));
        if (check.status === 404) { pairCode = candidate; pairStub = candidateStub; break; }
      }
      if (!pairCode || !pairStub) return json({ error: 'unable to allocate pairing code' }, 503);

      const expiresAt = new Date(Date.now() + 10 * 60 * 1000).toISOString();
      await pairStub.fetch(new Request('https://bedtime-state.internal/pair', {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ childId, recoveryPin, parentTokenHash, expiresAt })
      }));
      return json({ ok: true, childId, pairCode, parentToken, expiresAt });
    }

    if (url.pathname === '/api/pairing/claim' && request.method === 'POST') {
      let body;
      try { body = await request.json(); } catch { return json({ error: 'invalid json' }, 400); }
      const pairCode = String(body?.pairCode || '').trim();
      if (!/^\d{6}$/.test(pairCode)) return json({ error: 'invalid pairing code' }, 400);
      const pairId = env.BEDTIME_STATE_DO.idFromName(`pair:${pairCode}`);
      const pairStub = env.BEDTIME_STATE_DO.get(pairId);
      const pendingResponse = await pairStub.fetch(new Request('https://bedtime-state.internal/pair', { method: 'GET' }));
      if (!pendingResponse.ok) return json({ error: 'pairing code not found' }, 404);
      const pending = await pendingResponse.json();
      if (!pending.expiresAt || Date.parse(pending.expiresAt) <= Date.now()) {
        await pairStub.fetch(new Request('https://bedtime-state.internal/pair', { method: 'DELETE' }));
        return json({ error: 'pairing code expired' }, 410);
      }

      const childToken = randomToken(32);
      const childTokenHash = await sha256(childToken);
      const child = await childStub(env, pending.childId);
      await child.fetch(new Request(`https://bedtime-state.internal/credentials?childId=${encodeURIComponent(pending.childId)}`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ childTokenHash, parentTokenHash: pending.parentTokenHash, paired: true, pairedAt: new Date().toISOString() })
      }));
      await pairStub.fetch(new Request('https://bedtime-state.internal/pair', { method: 'DELETE' }));
      return json({ ok: true, childId: pending.childId, childToken, recoveryPin: pending.recoveryPin });
    }

    if (url.pathname === '/api/pairing/status' && request.method === 'GET') {
      const childId = String(url.searchParams.get('childId') || '').trim();
      if (!/^[A-Za-z0-9._-]{1,80}$/.test(childId)) return json({ error: 'invalid childId' }, 400);
      const token = request.headers.get('x-parent-token') || '';
      if (!token) return json({ error: 'missing parent token' }, 401);
      const credentials = await getCredentials(env, childId);
      if (!credentials) return json({ childId, paired: false });
      if (!(await tokenMatches(token, credentials.parentTokenHash))) return json({ error: 'unauthorized' }, 401);
      return json({ childId, paired: credentials.paired === true, pairedAt: credentials.pairedAt || null });
    }

    const match = url.pathname.match(/^\/api\/children\/([^/]+)\/bedtime$/);
    if (!match) return json({ error: 'not found' }, 404);
    const childId = decodeURIComponent(match[1]);
    if (!/^[A-Za-z0-9._-]{1,80}$/.test(childId)) return json({ error: 'invalid childId' }, 400);
    const credentials = await getCredentials(env, childId);

    if (request.method === 'POST') {
      if (!(await authorizedParent(request, credentials, env))) return json({ error: 'unauthorized' }, 401);
      let body;
      try { body = await request.json(); } catch { return json({ error: 'invalid json' }, 400); }
      if (typeof body?.active !== 'boolean') return json({ error: 'active must be boolean' }, 400);
      if (body.allowPowerControls !== undefined && typeof body.allowPowerControls !== 'boolean') return json({ error: 'allowPowerControls must be boolean' }, 400);
      const stub = await childStub(env, childId);
      return stub.fetch(new Request(`https://bedtime-state.internal/state?childId=${encodeURIComponent(childId)}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }));
    }
    if (request.method === 'GET') {
      if (!(await authorizedForRead(request, credentials))) return json({ error: 'unauthorized' }, 401);
      const stub = await childStub(env, childId);
      return stub.fetch(new Request(`https://bedtime-state.internal/state?childId=${encodeURIComponent(childId)}`, { method: 'GET', headers: { 'cache-control': 'no-store' } }));
    }
    return json({ error: 'method not allowed' }, 405);
  }
};
