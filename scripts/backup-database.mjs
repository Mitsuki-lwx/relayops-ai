import { spawn } from 'node:child_process';
import { createWriteStream, createReadStream } from 'node:fs';
import { mkdir, rename, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { createHash } from 'node:crypto';
import { mysqlTools } from './mysql-tools.mjs';

const database = process.argv[2] ?? 'relayops';
const output = resolve(process.argv[3] ?? `artifacts/backups/relayops-${new Date().toISOString().replace(/[:.]/g, '-')}.sql`);
if (!/^relayops(?:_[a-z0-9_]+)?$/.test(database)) throw new Error('Refuse unexpected database name');
await mkdir(dirname(output), { recursive: true });
const partial = output + '.partial';
const tools = mysqlTools();
const [command, args] = tools.dump(database);
const dump = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
dump.stderr.resume(); // Never print database diagnostics or credentials.
const done = new Promise((res, rej) => { dump.on('error', rej); dump.on('close', code => code === 0 ? res() : rej(new Error('Database backup failed; partial file retained'))); });
await Promise.all([pipeline(dump.stdout, createWriteStream(partial, { flags: 'wx', mode: 0o600 })), done]);
// Refuse replacing an earlier backup; the exclusive partial file also prevents concurrent writers.
try { const handle = await import('node:fs/promises').then(fs => fs.open(output, 'wx', 0o600)); await handle.close(); }
catch { throw new Error('Backup target exists; partial file retained'); }
await rename(partial, output);
const hash = createHash('sha256'); for await (const chunk of createReadStream(output)) hash.update(chunk);
await writeFile(output + '.sha256', hash.digest('hex') + '\n', { flag: 'wx', mode: 0o600 });
console.log(JSON.stringify({ database, backend: tools.backend, backup: output, checksum: output + '.sha256' }));
