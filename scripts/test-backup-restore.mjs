import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { mysqlTools } from './mysql-tools.mjs';

// Only this explicit test source can be restored, only into a brand-new private schema.
const started = Date.now();
const target = `relayops_restore_${Date.now()}`;
assert.match(target, /^relayops_restore_[0-9]+$/);
const file = `artifacts/backups/rehearsal-${Date.now()}.sql`;
mkdirSync('artifacts/backups', { recursive: true });
const backupStarted = Date.now();
execFileSync(process.execPath, ['scripts/backup-database.mjs', 'relayops_test', file], { stdio: 'pipe' });
const backupMs = Date.now() - backupStarted;
const tools = mysqlTools();
const sql = tools.query;
assert.equal(sql(`SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${target}'`).toString().trim(), '0');
sql(`CREATE DATABASE ${target} CHARACTER SET utf8mb4;`);
try {
  const restoreStarted = Date.now();
  sql(readFileSync(file), target);
  const restoreMs = Date.now() - restoreStarted;
  const restored = file + '.restored.sql';
  const redumpStarted = Date.now();
  execFileSync(process.execPath, ['scripts/backup-database.mjs', target, restored], { stdio: 'pipe' });
  const redumpMs = Date.now() - redumpStarted;
  const digest = path => createHash('sha256').update(readFileSync(path)).digest('hex');
  assert.equal(digest(file), digest(restored), 'Restored schema, rows and ciphertext must match the ordered source dump');
  const tables = Number(sql(`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${target}'`).toString().trim());
  const report={source:'relayops_test',restored:target,tables,keys:['schema','rows','ciphertext'],dumpBytes:readFileSync(file).length,backupMs,restoreMs,redumpMs,totalMs:Date.now()-started,dumpAndCiphertextMatch:true,mainDatabaseWritten:false};
  const reportFile=`artifacts/backups/restore-drill-${Date.now()}.json`;
  writeFileSync(reportFile,JSON.stringify(report,null,2));
  console.log(JSON.stringify({...report,reportFile}));
} finally {
  // target is generated above, checked, observed absent, and created by this invocation only.
  sql(`DROP DATABASE ${target};`);
}
