import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { mysqlTools } from './mysql-tools.mjs';

// Only this explicit test source can be restored, only into a brand-new private schema.
const target = `relayops_restore_${Date.now()}`;
assert.match(target, /^relayops_restore_[0-9]+$/);
const file = `artifacts/backups/rehearsal-${Date.now()}.sql`;
mkdirSync('artifacts/backups', { recursive: true });
execFileSync(process.execPath, ['scripts/backup-database.mjs', 'relayops_test', file], { stdio: 'pipe' });
const tools = mysqlTools();
const sql = tools.query;
assert.equal(sql(`SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${target}'`).toString().trim(), '0');
sql(`CREATE DATABASE ${target} CHARACTER SET utf8mb4;`);
try {
  sql(readFileSync(file), target);
  const restored = file + '.restored.sql';
  execFileSync(process.execPath, ['scripts/backup-database.mjs', target, restored], { stdio: 'pipe' });
  const digest = path => createHash('sha256').update(readFileSync(path)).digest('hex');
  assert.equal(digest(file), digest(restored), 'Restored schema, rows and ciphertext must match the ordered source dump');
  const tables = Number(sql(`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${target}'`).toString().trim());
  console.log(JSON.stringify({ restored: target, tables, dumpAndCiphertextMatch: true, mainDatabaseWritten: false }));
} finally {
  // target is generated above, checked, observed absent, and created by this invocation only.
  sql(`DROP DATABASE ${target};`);
}
