const express = require('express');
const app = express();
app.use(express.json());

const bedtime = new Map();

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
