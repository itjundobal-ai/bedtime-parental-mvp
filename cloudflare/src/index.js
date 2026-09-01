function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
      'access-control-allow-headers': 'content-type,x-parent-key'
    }
  });
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return json({ ok: true });

    const url = new URL(request.url);
    if (url.pathname === '/health') {
      return json({ ok: true, service: 'bedtime-parental-worker' });
    }

    const match = url.pathname.match(/^\/api\/children\/([^/]+)\/bedtime$/);
    if (!match) return json({ error: 'not found' }, 404);

    const childId = decodeURIComponent(match[1]);
    if (!/^[A-Za-z0-9._-]{1,80}$/.test(childId)) {
      return json({ error: 'invalid childId' }, 400);
    }

    const key = `bedtime:${childId}`;

    if (request.method === 'GET') {
      const stored = await env.BEDTIME_STATE.get(key, { type: 'json' });
      return json({
        childId,
        active: stored?.active === true,
        updatedAt: stored?.updatedAt || null
      });
    }

    if (request.method === 'POST') {
      if (env.PARENT_API_KEY) {
        const provided = request.headers.get('x-parent-key') || '';
        if (provided !== env.PARENT_API_KEY) return json({ error: 'unauthorized' }, 401);
      }

      let body;
      try { body = await request.json(); }
      catch { return json({ error: 'invalid json' }, 400); }

      if (typeof body?.active !== 'boolean') {
        return json({ error: 'active must be boolean' }, 400);
      }

      const state = {
        active: body.active,
        updatedAt: new Date().toISOString()
      };
      await env.BEDTIME_STATE.put(key, JSON.stringify(state));
      return json({ ok: true, childId, ...state });
    }

    return json({ error: 'method not allowed' }, 405);
  }
};
