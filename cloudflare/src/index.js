// Cloud pairing + bedtime control for Parent/Child devices.
function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
      'access-control-allow-headers': 'content-type,x-parent-key,x-parent-token,x-child-token'
    }
  });
}

function randomToken(bytes = 24) {
  const b = new Uint8Array(bytes);
  crypto.getRandomValues(b);
  return Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
}
function randomCode() {
  const b = new Uint32Array(1);
  crypto.getRandomValues(b);
  return String(b[0] % 1000000).padStart(6, '0');
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return json({ ok: true });
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') return json({ ok: true, service: 'bedtime-parental-worker' });

    if (url.pathname === '/api/pairing/create' && request.method === 'POST') {
      const code = randomCode();
      const parentToken = randomToken(24);
      const childId = 'child-' + randomToken(8).slice(0, 12);
      await env.BEDTIME_STATE.put('paircode:' + code, JSON.stringify({ parentToken, childId }), { expirationTtl: 900 });
      await env.BEDTIME_STATE.put('parent:' + parentToken, JSON.stringify({ childId, children: [childId] }), { expirationTtl: 31536000 });
      return json({ ok: true, code, childId, parentToken, expiresIn: 900 });
    }

    if (url.pathname === '/api/pairing/redeem' && request.method === 'POST') {
      let body; try { body = await request.json(); } catch { return json({ error: 'invalid json' }, 400); }
      const code = String(body?.code || '').trim();
      if (!/^\d{6}$/.test(code)) return json({ error: 'pairing code must be 6 digits' }, 400);
      const pair = await env.BEDTIME_STATE.get('paircode:' + code, { type: 'json' });
      if (!pair?.parentToken || !pair?.childId) return json({ error: 'pairing code expired or invalid' }, 404);
      const childToken = randomToken(24);
      await env.BEDTIME_STATE.put('child:' + pair.childId, JSON.stringify({ childToken, parentToken: pair.parentToken }), { expirationTtl: 31536000 });
      const parentKey = 'parent:' + pair.parentToken;
      const parent = await env.BEDTIME_STATE.get(parentKey, { type: 'json' }) || { children: [] };
      const children = Array.isArray(parent.children) ? parent.children : [];
      if (!children.includes(pair.childId)) children.push(pair.childId);
      await env.BEDTIME_STATE.put(parentKey, JSON.stringify({ ...parent, childId: pair.childId, children }), { expirationTtl: 31536000 });
      await env.BEDTIME_STATE.delete('paircode:' + code);
      return json({ ok: true, childId: pair.childId, childToken, paired: true });
    }

    if (url.pathname === '/api/pairing/status' && request.method === 'GET') {
      const parentToken = request.headers.get('x-parent-token') || '';
      if (!parentToken) return json({ error: 'missing parent token' }, 401);
      const parent = await env.BEDTIME_STATE.get('parent:' + parentToken, { type: 'json' });
      if (!parent) return json({ error: 'invalid parent token' }, 401);
      const ids = Array.isArray(parent.children) ? parent.children : (parent.childId ? [parent.childId] : []);
      const children = [];
      for (const id of ids) {
        const record = await env.BEDTIME_STATE.get('child:' + id, { type: 'json' });
        children.push({ childId: id, paired: !!record?.childToken });
      }
      return json({ ok: true, children });
    }

    const match = url.pathname.match(/^\/api\/children\/([^/]+)\/bedtime$/);
    if (!match) return json({ error: 'not found' }, 404);
    const childId = decodeURIComponent(match[1]);
    if (!/^[A-Za-z0-9._-]{1,80}$/.test(childId)) return json({ error: 'invalid childId' }, 400);
    const key = `bedtime:${childId}`;

    if (request.method === 'GET') {
      const childRecord = await env.BEDTIME_STATE.get('child:' + childId, { type: 'json' });
      if (childRecord?.childToken) {
        const provided = request.headers.get('x-child-token') || '';
        if (provided !== childRecord.childToken) return json({ error: 'unauthorized' }, 401);
      }
      const stored = await env.BEDTIME_STATE.get(key, { type: 'json' });
      return json({ childId, active: stored?.active === true, allowPowerControls: stored?.allowPowerControls === true, updatedAt: stored?.updatedAt || null });
    }

    if (request.method === 'POST') {
      let authorized = false;
      const providedParentToken = request.headers.get('x-parent-token') || '';
      if (providedParentToken) {
        const parentRecord = await env.BEDTIME_STATE.get('parent:' + providedParentToken, { type: 'json' });
        const ids = Array.isArray(parentRecord?.children) ? parentRecord.children : (parentRecord?.childId ? [parentRecord.childId] : []);
        authorized = ids.includes(childId);
      }
      if (!authorized && env.PARENT_API_KEY) authorized = (request.headers.get('x-parent-key') || '') === env.PARENT_API_KEY;
      if (!authorized && env.PARENT_API_KEY) return json({ error: 'unauthorized' }, 401);
      let body; try { body = await request.json(); } catch { return json({ error: 'invalid json' }, 400); }
      if (typeof body?.active !== 'boolean') return json({ error: 'active must be boolean' }, 400);
      if (body.allowPowerControls !== undefined && typeof body.allowPowerControls !== 'boolean') return json({ error: 'allowPowerControls must be boolean' }, 400);
      const previous = await env.BEDTIME_STATE.get(key, { type: 'json' });
      const state = { active: body.active, allowPowerControls: body.allowPowerControls === true || (body.allowPowerControls === undefined && previous?.allowPowerControls === true), updatedAt: new Date().toISOString() };
      await env.BEDTIME_STATE.put(key, JSON.stringify(state));
      return json({ ok: true, childId, ...state });
    }
    return json({ error: 'method not allowed' }, 405);
  }
};