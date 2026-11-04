#!/usr/bin/env node
// Read-only inventory for metadata older than N days. It never deletes rows.
import { mysqlTools } from './mysql-tools.mjs';

const args={database:'relayops_test',days:30,json:false};
for(let i=2;i<process.argv.length;i++){
 const token=process.argv[i], next=()=>process.argv[++i];
 if(token==='--database')args.database=next();
 else if(token==='--days')args.days=Number(next());
 else if(token==='--json')args.json=true;
 else if(token==='--help'||token==='-h'){console.log('Usage: node scripts/metadata-retention-inventory.mjs [--database relayops_test] [--days 30] [--json]');process.exit(0);}
 else throw new Error(`Unknown argument: ${token}`);
}
if(!/^[A-Za-z0-9_]+$/.test(args.database))throw new Error('Invalid database name');
if(!Number.isInteger(args.days)||args.days<1||args.days>3650)throw new Error('--days must be an integer between 1 and 3650');
const q=mysqlTools().query, quote=value=>"'"+value.replaceAll("'","''")+"'";
const n=sql=>Number(q(sql,args.database).toString().trim());
const terminal="status IN ('SUCCEEDED','FAILED','UNKNOWN','CANCELLED')";
const cutoff=`DATE_SUB(UTC_TIMESTAMP(3),INTERVAL ${args.days} DAY)`;
const jobs=`SELECT id FROM ai_job WHERE ${terminal} AND created_at<${cutoff}`;
const count=label=>n(`SELECT COUNT(*) FROM (${jobs}) x`)+(label?0:0);
const inventory={
 database:args.database,days:args.days,cutoffDays:args.days,readOnly:true,
 jobs:count('jobs'),
 results:n(`SELECT COUNT(*) FROM ai_result r JOIN (${jobs}) j ON j.id=r.job_id`),
 attempts:n(`SELECT COUNT(*) FROM ai_attempt a JOIN (${jobs}) j ON j.id=a.job_id`),
 budgetEntries:n(`SELECT COUNT(*) FROM budget_entry b JOIN (${jobs}) j ON j.id=b.job_id`),
 idempotency:n(`SELECT COUNT(*) FROM idempotency_record i JOIN (${jobs}) j ON j.id=i.job_id`),
 outbox:n(`SELECT COUNT(*) FROM outbox_event o JOIN (${jobs}) j ON j.id=o.aggregate_id`),
 jobDestinations:n(`SELECT COUNT(*) FROM job_destination d JOIN (${jobs}) j ON j.id=d.job_id`),
 deliveries:n(`SELECT COUNT(*) FROM delivery d JOIN (${jobs}) j ON j.id=d.job_id`),
 deliveryRuns:n(`SELECT COUNT(*) FROM delivery_run r JOIN delivery d ON d.id=r.delivery_id JOIN (${jobs}) j ON j.id=d.job_id`),
 deliveryAttempts:n(`SELECT COUNT(*) FROM delivery_attempt a JOIN delivery_run r ON r.id=a.run_id JOIN delivery d ON d.id=r.delivery_id JOIN (${jobs}) j ON j.id=d.job_id`),
 deliveryOutbox:n(`SELECT COUNT(*) FROM delivery_outbox o JOIN delivery d ON d.id=o.aggregate_id JOIN (${jobs}) j ON j.id=d.job_id`),
 childJobs:n(`SELECT COUNT(*) FROM ai_job c JOIN (${jobs}) j ON j.id=c.parent_job_id`),
 auditEvents:n(`SELECT COUNT(*) FROM audit_event a JOIN (${jobs}) j ON j.id=a.resource_id`)
};
if(args.json)console.log(JSON.stringify(inventory,null,2));
else{console.log(`Metadata retention inventory (read-only) · ${args.database} · older than ${args.days} days`);for(const [key,value] of Object.entries(inventory))if(typeof value==='number')console.log(`${key}: ${value}`);console.log('No rows were deleted.');}
