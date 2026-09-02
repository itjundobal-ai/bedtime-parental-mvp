// Redeploy marker: keep child state GET public; protect parent writes only.
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

function dashboard() {
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>Bedtime Parent Control</title>
<style>
body{font-family:Arial,sans-serif;background:#0f172a;color:#fff;margin:0;min-height:100vh;display:grid;place-items:center}
.card{width:min(92vw,440px);background:#111827;padding:28px;border-radius:22px;box-shadow:0 20px 50px rgba(0,0,0,.35)}
h1{margin:0 0 8px;font-size:28px}.muted{color:#94a3b8;margin-bottom:24px}
label{display:block;margin:14px 0 6px;font-size:14px;color:#cbd5e1}input{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #334155;background:#0b1220;color:white;font-size:16px}
.row{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:20px}button{border:0;border-radius:14px;padding:16px;font-size:17px;font-weight:700;cursor:pointer}.on{background:#7c3aed;color:white}.off{background:#334155;color:white}
.toggle{display:flex;align-items:center;gap:10px;margin-top:18px;padding:14px;border-radius:12px;background:#0b1220}.toggle input{width:auto;transform:scale(1.25)}.toggle label{margin:0;color:#e2e8f0}.hint{font-size:12px;color:#94a3b8;margin-top:6px}
.status{margin-top:18px;padding:14px;border-radius:12px;background:#0b1220;color:#cbd5e1;min-height:20px}.active{color:#fbbf24;font-weight:700}.inactive{color:#86efac;font-weight:700}
</style>
</head>
<body><main class="card">
<h1>🌙 Bedtime Control</h1><div class="muted">Parent web dashboard</div>
<label>Child ID</label><input id="child" value="child-001" autocomplete="off" />
<label>Parent Key <span style="color:#64748b">(if enabled)</span></label><input id="key" type="password" placeholder="Optional" />
<div class="toggle"><input id="allowPower" type="checkbox" /><div><label for="allowPower">Allow power controls during Bedtime</label><div class="hint">Parent-controlled setting. In managed/kiosk mode this will allow Android global power/restart controls.</div></div></div>
<div class="row"><button class="on" onclick="setBedtime(true)">BEDTIME ON</button><button class="off" onclick="setBedtime(false)">BEDTIME OFF</button></div>
<div id="status" class="status">Ready</div>
</main>
<script>
const child=document.getElementById('child'), key=document.getElementById('key'), allowPower=document.getElementById('allowPower'), statusEl=document.getElementById('status');
child.value=localStorage.getItem('childId')||'child-001'; key.value=localStorage.getItem('parentKey')||''; allowPower.checked=localStorage.getItem('allowPowerControls')==='true';
async function setBedtime(active){
 const id=child.value.trim(); if(!id){statusEl.textContent='Enter Child ID';return;}
 localStorage.setItem('childId',id); localStorage.setItem('parentKey',key.value); localStorage.setItem('allowPowerControls',String(allowPower.checked));
 statusEl.textContent=active?'Sending Bedtime ON…':'Sending Bedtime OFF…';
 try{
  const headers={'content-type':'application/json'}; if(key.value) headers['x-parent-key']=key.value;
  const r=await fetch('/api/children/'+encodeURIComponent(id)+'/bedtime',{method:'POST',headers,body:JSON.stringify({active,allowPowerControls:allowPower.checked})});
  const d=await r.json(); if(!r.ok) throw new Error(d.error||('HTTP '+r.status));
  statusEl.innerHTML=(active?'<span class="active">🌙 Bedtime is ON</span>':'<span class="inactive">✓ Bedtime is OFF</span>')+'<div class="hint">Power controls: '+(d.allowPowerControls?'ALLOWED':'NOT ALLOWED')+'</div>';
 }catch(e){statusEl.textContent='Error: '+e.message;}
}
</script></body></html>`;
  return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8' } });
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return json({ ok: true });
    const url = new URL(request.url);

    if (url.pathname === '/' && request.method === 'GET') return dashboard();
    if (url.pathname === '/health') return json({ ok: true, service: 'bedtime-parental-worker' });

    const match = url.pathname.match(/^\/api\/children\/([^/]+)\/bedtime$/);
    if (!match) return json({ error: 'not found' }, 404);

    const childId = decodeURIComponent(match[1]);
    if (!/^[A-Za-z0-9._-]{1,80}$/.test(childId)) return json({ error: 'invalid childId' }, 400);
    const key = `bedtime:${childId}`;

    if (request.method === 'GET') {
      const stored = await env.BEDTIME_STATE.get(key, { type: 'json' });
      return json({
        childId,
        active: stored?.active === true,
        allowPowerControls: stored?.allowPowerControls === true,
        updatedAt: stored?.updatedAt || null
      });
    }

    if (request.method === 'POST') {
      if (env.PARENT_API_KEY) {
        const provided = request.headers.get('x-parent-key') || '';
        if (provided !== env.PARENT_API_KEY) return json({ error: 'unauthorized' }, 401);
      }
      let body;
      try { body = await request.json(); } catch { return json({ error: 'invalid json' }, 400); }
      if (typeof body?.active !== 'boolean') return json({ error: 'active must be boolean' }, 400);
      if (body.allowPowerControls !== undefined && typeof body.allowPowerControls !== 'boolean') return json({ error: 'allowPowerControls must be boolean' }, 400);
      const previous = await env.BEDTIME_STATE.get(key, { type: 'json' });
      const state = {
        active: body.active,
        allowPowerControls: body.allowPowerControls === true || (body.allowPowerControls === undefined && previous?.allowPowerControls === true),
        updatedAt: new Date().toISOString()
      };
      await env.BEDTIME_STATE.put(key, JSON.stringify(state));
      return json({ ok: true, childId, ...state });
    }

    return json({ error: 'method not allowed' }, 405);
  }
};
