import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mysqlTools } from './mysql-tools.mjs';

async function main() {
  const native = mysqlTools('native'), migrate = process.argv.includes('--migrate-docker');
  const original = readFileSync('ops/.env','utf8');
  const config = Object.fromEntries(original.split(/\r?\n/).filter(l => /^[A-Z_]+=/.test(l)).map(l => [l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1)]));
  const adminFile = process.env.RELAYOPS_MYSQL_ADMIN_CONFIG ?? 'ops/mysql-native.cnf';
  const nativeConfig = Object.fromEntries(readFileSync(adminFile,'utf8').split(/\r?\n/).filter(l => /^\s*(host|port)\s*=/.test(l)).map(l => l.split('=').map(s=>s.trim())));
  const host = nativeConfig.host ?? '127.0.0.1', port = nativeConfig.port ?? '3306';
  if (!['127.0.0.1','localhost'].includes(host) || !/^[0-9]{1,5}$/.test(port)) throw new Error('LOCAL_CONNECTION_REQUIRED');
  const version = native.query('SELECT VERSION();').toString().trim();
  const numbers = version.split('.').slice(0,3).map(v=>parseInt(v));
  if (numbers[0]!==8 || (numbers[1]===0 && numbers[2]<21)) throw new Error('MYSQL_8_0_21_OR_NEWER_REQUIRED');
  if (native.query("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='relayops';").toString().trim()!=='0') throw new Error('TARGET_DATABASE_EXISTS_REFUSE_OVERWRITE');
  if (native.query("SELECT COUNT(*) FROM mysql.user WHERE User='relayops_local';").toString().trim()!=='0') throw new Error('TARGET_APP_USER_EXISTS_REFUSE_OVERWRITE');
  const password = config.DB_PASSWORD;
  if (!password || password.length<12) throw new Error('APP_PASSWORD_REQUIRED');
  const quote = s=>"'"+s.replaceAll("'","''")+"'";
  const directory = `artifacts/native-migration/${Date.now()}`; mkdirSync(directory,{recursive:true});
  writeFileSync(directory+'/.env.before',original,{flag:'wx',mode:0o600});
  let source;
  if(migrate) {
    source=mysqlTools('docker');
    execFileSync(process.execPath,['scripts/backup-database.mjs','relayops',directory+'/source.sql'],{env:{...process.env,RELAYOPS_DATABASE_BACKEND:'docker'},stdio:'pipe'});
  }
  native.query('CREATE DATABASE relayops CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;');
  if(migrate) {
    native.query(readFileSync(directory+'/source.sql'),'relayops');
    const tables=source.query('SHOW TABLES;','relayops').toString().trim().split(/\r?\n/);
    const hashes={};
    for(const table of tables) {
      if(!/^[a-z0-9_]+$/.test(table))throw new Error('UNEXPECTED_TABLE');
      const columns=source.query(`SELECT column_name FROM information_schema.columns WHERE table_schema='relayops' AND table_name='${table}' ORDER BY ordinal_position;`).toString().trim().split(/\r?\n/);
      if(columns.some(c=>!/^[a-z0-9_]+$/.test(c)))throw new Error('UNEXPECTED_COLUMN');
      const sql="SET time_zone='+00:00'; SELECT CONCAT_WS('|',"+columns.map(c=>"COALESCE(HEX(`"+c+"`),'NULL')").join(',')+') FROM `'+table+'`;';
      const hash=tools=>createHash('sha256').update(tools.query(sql,'relayops').toString().trim().split(/\r?\n/).sort().join('\n')).digest('hex');
      const expected=hash(source);if(hash(native)!==expected)throw new Error('MIGRATION_DATA_HASH_MISMATCH');hashes[table]=expected;
    }
    writeFileSync(directory+'/hashes.json',JSON.stringify(hashes,null,2),{mode:0o600});
  }
  for(const accountHost of ['localhost','127.0.0.1']) {
    native.query(`SET sql_mode='NO_BACKSLASH_ESCAPES'; CREATE USER 'relayops_local'@'${accountHost}' IDENTIFIED BY ${quote(password)}; GRANT ALL PRIVILEGES ON relayops.* TO 'relayops_local'@'${accountHost}';`);
  }
  execFileSync('mysql',['--no-defaults',`--host=${host}`,`--port=${port}`,'--user=relayops_local','--connect-timeout=5','--execute=SELECT 1','relayops'],{env:{...process.env,MYSQL_PWD:password},stdio:'pipe'});
  let updated=original;
  for(const [key,value] of Object.entries({DB_USERNAME:'relayops_local',DB_URL:`jdbc:mysql://${host}:${port}/relayops?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`})) {
    const pattern=new RegExp('^'+key+'=.*$','m');updated=pattern.test(updated)?updated.replace(pattern,key+'='+value):updated+'\n'+key+'='+value+'\n';
  }
  writeFileSync('ops/.env',updated,{mode:0o600});
  console.log(JSON.stringify({backend:'native',host,port:Number(port),version,migrated:migrate,sourceContainerPreserved:true,configurationUpdated:true}));
}
main().catch(error=>{console.error(error.message && /^[A-Z0-9_]+$/.test(error.message)?error.message:'NATIVE_MYSQL_SETUP_FAILED; original connection preserved; inspect local server configuration');process.exitCode=1;});
