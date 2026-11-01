import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

export function mysqlTools(backend = process.env.RELAYOPS_DATABASE_BACKEND ?? (existsSync('ops/mysql-native.cnf') ? 'native' : 'docker')) {
  if (!['native', 'docker'].includes(backend)) throw new Error('Database backend must be native or docker');
  const config = resolve(process.env.RELAYOPS_MYSQL_ADMIN_CONFIG ?? 'ops/mysql-native.cnf');
  if (backend === 'native' && !existsSync(config)) throw new Error('Configure ops/mysql-native.cnf locally first');
  const mysql = database => backend === 'native'
    ? ['mysql', [`--defaults-extra-file=${config}`, '--connect-timeout=5', '--batch', '--raw', '--skip-column-names', ...(database ? [database] : [])]]
    : ['docker', ['exec', '-i', 'relayops-mysql-1', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names "$@"', 'relayops-mysql', ...(database ? [database] : [])]];
  const flags = ['--single-transaction', '--skip-lock-tables', '--no-tablespaces', '--set-gtid-purged=OFF', '--hex-blob', '--skip-comments', '--skip-extended-insert', '--order-by-primary', '--column-statistics=0'];
  const dump = database => backend === 'native'
    ? ['mysqldump', [`--defaults-extra-file=${config}`, ...flags, database]]
    : ['docker', ['exec', 'relayops-mysql-1', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot "$@"', 'relayops-dump', ...flags, database]];
  const query = (sql, database) => { const [command, args] = mysql(database); return execFileSync(command, args, { input: sql, maxBuffer: 128 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'] }); };
  return { backend, mysql, dump, query };
}
