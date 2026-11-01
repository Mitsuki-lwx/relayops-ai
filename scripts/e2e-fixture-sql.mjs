import { readFileSync } from 'node:fs';
import { mysqlTools } from './mysql-tools.mjs';
const database = process.env.RELAYOPS_E2E_DATABASE;
if (!/^relayops_e2e_[0-9]+$/.test(database ?? '')) throw new Error('Refuse fixture SQL outside a dedicated browser schema');
mysqlTools().query(readFileSync(0), database);
