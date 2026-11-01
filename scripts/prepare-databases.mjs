import { mysqlTools } from './mysql-tools.mjs';
const tools=mysqlTools(),user=process.env.DB_USERNAME??'relayops';
if(!/^[A-Za-z0-9_]+$/.test(user))throw new Error('Invalid application database account');
const quote=s=>"'"+s.replaceAll("'","''")+"'";
const hosts=tools.query(`SELECT Host FROM mysql.user WHERE User=${quote(user)}`).toString().trim().split(/\r?\n/).filter(Boolean);
if(!hosts.length)throw new Error('Configure the application database user first');
for(const database of ['relayops_test','relayops_messaging_test','relayops_upgrade_test']) {
  tools.query(`CREATE DATABASE IF NOT EXISTS ${database} CHARACTER SET utf8mb4;`);
  for(const host of hosts)tools.query(`SET sql_mode='NO_BACKSLASH_ESCAPES'; GRANT ALL PRIVILEGES ON ${database}.* TO ${quote(user)}@${quote(host)};`);
}
console.log(JSON.stringify({backend:tools.backend,testSchemas:3,mainDatabaseWritten:false}));
