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

function dashboard() {
  const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8" /><meta name="viewport" content="width=device-width,initial-scale=1" />
<title>Bedtime Parent Control</title>
<style>body{font-family:Arial,sans-serif;background:#0f172a;color:#fff;margin:0;min-height:100vh;display:grid;place-items:center}.card{width:min(92vw,440px);background:#111827;padding:28px;border-radius:22px;box-shadow:0 20px 50px rgba(0,0,0,.35)}h1{margin:0 0 8px;font-size:28px}.muted{color:#94a3b8;margin-bottom:24px}label{display:block;margin:14px 0 6px;font-size:14px;color:#cbd5e1}input{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #334155;background:#0b1220;color:white;font-size:16px}.row{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:20px}button{border:0;border-radius:14px;padding:16px;font-size:17px;font-weight:700;cursor:pointer}.on{background:#7c3aed;color:white}.off{background:#334155;color:white}.pair{background:#059669;color:white;width:100%;margin-top:12px}.code{font-size:36px;letter-spacing:8px;text-align:center;margin:12px 0;color:#fbbf24;font-weight:800}.status{margin-top:18px;padding:14px;border-radius:12px;background:#0b1220;color:#cbd5e1;min-height:20px}.hint{font-size:12px;color:#94a3b8;margin-top:6px}</style></head>
<body><main class="card"><h1>🌙 Bedtime Control</h1><div class="muted">Parent web dashboard</div>
<button class="pair" onclick="createPairing()">GENERATE CHILD PAIRING CODE</button><div id="pairCode"></div>
<label>Child ID</label><input id="child" value="child-001" autocomplete="off" />
<label>Parent Key <span style="color:#64748b">(legacy, if enabled)</span></label><input id="key" type="password" placeholder="Optional" />
<div class="row"><button class="on" onclick="setBedtime(true)">BEDTIME ON</button><button class="off" onclick="setBedtime(false)">BEDTIME OFF</button></div>
<div id="status" class="status">Ready</div></main>
<script>
const child=document.getElementById('child'), key=document.getElementById('key'), statusEl=document.getElementById('status'), pairCode=document.getElementById('pairCode');
child.value=localStorage.getItem('childId')||'child-001'; key.value=localStorage.getItem('parentKey')||'';
async function createPairing(){statusEl.textContent='Generating secure pairing code…';try{const r=await fetch('/api/pairing/create',{method:'POST',headers:{'content-type':'application/json'},body:'{}'});const d=await r.json();if(!r.ok)throw new Error(d.error||('HTTP '+r.status));localStorage.setItem('parentToken',d.parentToken);pairCode.innerHTML='<div class="hint">Enter this code on the Child phone. Expires in 15 minutes.</div><div class="code">'+d.code+'</div><div class="hint">Child ID after pairing: '+d.childId+'</div>';child.value=d.childId;localStorage.setItem('childId',d.childId);statusEl.textContent='Waiting for Child to enter the code…';}catch(e){statusEl.textContent='Pairing error: '+e.message;}}
async function setBedtime(active){const id=child.value.trim();if(!id){statusEl.textContent='Enter Child ID';return;}localStorage.setItem('childId',id);localStorage.setItem('parentKey',key.value);statusEl.textContent=active?'Sending Bedtime ON…':'Sending Bedtime OFF…';try{const headers={'content-type':'application/json'};const token=localStorage.getItem('parentToken')||'';if(token)headers['x-parent-token']=token;if(key.value)headers['x-parent-key']=key.value;const r=await fetch('/api/children/'+encodeURIComponent(id)+'/bedtime',{method:'POST',headers,body:JSON.stringify({active})});const d=await r.json();if(!r.ok)throw new Error(d.error||('HTTP '+r.status));statusEl.textContent=active?'🌙 Bedtime is ON':'✓ Bedtime is OFF';}catch(e){statusEl.textContent='Error: '+e.message;}}
</script></body></html>`;
  return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8' } });
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return json({ ok: true });
    const url = new URL(request.url);

    if (url.pathname === '/' && request.method === 'GET') return dashboard();
    if (url.pathname === '/health') return json({ ok: true, service: 'bedtime-parental-worker' });

    if (url.pathname === '/api/pairing/create' && request.method === 'POST') {
      const code = randomCode();
      const parentToken = randomToken(24);
      const childId = 'child-' + randomToken(8).slice(0, 12);
      await env.BEDTIME_STATE.put('paircode:' + code, JSON.stringify({ parentToken, childId }), { expirationTtl: 900 });
      await env.BEDTIME_STATE.put('parent:' + parentToken, JSON.stringify({ childId }), { expirationTtl: 31536000 });
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
      await env.BEDTIME_STATE.delete('paircode:' + code);
      return json({ ok: true, childId: pair.childId, childToken, paired: true });
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
        authorized = parentRecord?.childId === childId;
      }
      if (!authorized && env.PARENT_API_KEY) {
        const provided = request.headers.get('x-parent-key') || '';
        authorized = provided === env.PARENT_API_KEY;
      }
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
