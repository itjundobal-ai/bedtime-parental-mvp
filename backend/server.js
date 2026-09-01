const express = require('express');
const app = express();
app.use(express.json());

const bedtime = new Map();

function page() {
  return `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bedtime Parent Test</title>
<style>
body{font-family:Arial,sans-serif;background:#0f172a;color:#fff;margin:0;min-height:100vh;display:grid;place-items:center}
.card{width:min(92vw,430px);background:#111827;padding:28px;border-radius:22px;box-shadow:0 20px 50px rgba(0,0,0,.35)}
h1{margin:0 0 8px}.muted{color:#94a3b8;margin-bottom:22px}label{display:block;margin:12px 0 6px;color:#cbd5e1}input{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #334155;background:#0b1220;color:#fff;font-size:16px}
.row{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:18px}button{border:0;border-radius:14px;padding:16px;font-size:18px;font-weight:700;cursor:pointer}.lock{background:#dc2626;color:#fff}.unlock{background:#16a34a;color:#fff}.status{margin-top:16px;background:#0b1220;padding:14px;border-radius:12px;color:#cbd5e1}
</style></head>
<body><main class="card"><h1>🌙 Bedtime Parent Test</h1><div class="muted">Remote LOCK / UNLOCK</div>
<label>Child ID</label><input id="child" value="child-001">
<div class="row"><button class="lock" onclick="setBedtime(true)">LOCK</button><button class="unlock" onclick="setBedtime(false)">UNLOCK</button></div>
<div class="status" id="status">Ready</div></main>
<script>
const child=document.getElementById('child'); const statusEl=document.getElementById('status');
async function setBedtime(active){
 const id=child.value.trim(); if(!id){statusEl.textContent='Enter Child ID';return;}
 statusEl.textContent=active?'Sending LOCK...':'Sending UNLOCK...';
 try{
  const r=await fetch('/api/children/'+encodeURIComponent(id)+'/bedtime',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({active})});
  const d=await r.json(); if(!r.ok) throw new Error(d.error||('HTTP '+r.status));
  statusEl.textContent=active?'Bedtime LOCK sent':'Bedtime UNLOCK sent';
 }catch(e){statusEl.textContent='Error: '+e.message;}
}
</script></body></html>`;
}

app.get('/', (_req, res) => res.type('html').send(page()));
app.get('/health', (_req, res) => res.json({ok: true}));

app.get('/api/children/:childId/bedtime', (req, res) => {
  res.json({ childId: req.params.childId, active: bedtime.get(req.params.childId) === true });
});

app.post('/api/children/:childId/bedtime', (req, res) => {
  if (typeof req.body?.active !== 'boolean') {
    return res.status(400).json({error: 'active must be boolean'});
  }
  bedtime.set(req.params.childId, req.body.active);
  console.log(`[bedtime] ${req.params.childId} -> ${req.body.active ? 'ON' : 'OFF'}`);
  res.json({ok: true, childId: req.params.childId, active: req.body.active});
});

const port = process.env.PORT || 8080;
app.listen(port, '0.0.0.0', () => console.log(`Bedtime backend listening on :${port}`));
