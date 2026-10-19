const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const tree = JSON.parse(fs.readFileSync(path.join(__dirname, 'docs-tree.json'), 'utf8'));
const root = path.resolve(__dirname, '../../../qqhome-v16-docs-p0p1');
const result = { expectedCommit: tree.sha, root, verified: [], missing: [], different: [] };
for (const row of tree.tree.filter(row => row.type === 'blob' && row.path.startsWith('docs/life-os/'))) {
  const file = path.join(root, row.path);
  if (!fs.existsSync(file)) { result.missing.push(row.path); continue; }
  const raw = fs.readFileSync(file);
  // Git on this Windows checkout uses text CRLF conversion. Compare Git's canonical LF blob.
  const bytes = Buffer.from(raw.toString('utf8').replace(/\r\n/g, '\n'));
  const digest = crypto.createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
  (digest === row.sha ? result.verified : result.different).push(row.path);
}
fs.writeFileSync(path.join(__dirname, 'docs-verification.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ commit: result.expectedCommit, verified: result.verified.length, missing: result.missing, different: result.different }));
if (result.missing.length || result.different.length) process.exitCode = 1;
