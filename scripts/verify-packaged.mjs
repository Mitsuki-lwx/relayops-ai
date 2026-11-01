import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
if (!/^relayops_e2e_[0-9]+$/.test(process.env.RELAYOPS_E2E_DATABASE ?? '')) throw new Error('Packaged acceptance requires an isolated test schema');
const base = process.env.RELAYOPS_BROWSER_BASE;
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base ?? '')) throw new Error('Local test base required');
const config = Object.fromEntries(readFileSync(process.env.RELAYOPS_E2E_CONFIG, 'utf8').split(/\r?\n/).filter(l => l.includes('=')).map(l => [l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1)]));
function client() {
  let cookie = '', csrf;
  const request = async (path, init = {}) => {
    const headers = new Headers(init.headers);
    if (cookie) headers.set('Cookie', cookie);
    if (init.csrf && csrf) headers.set(csrf.headerName, csrf.token);
    const response = await fetch(base + path, { ...init, headers, signal: AbortSignal.timeout(10000) });
    const session = response.headers.getSetCookie().find(c => c.startsWith('JSESSIONID='));
    if (session) cookie = session.split(';')[0];
    return response;
  };
  const login = async username => {
    csrf = await (await request('/auth/csrf')).json();
    const response = await request('/auth/login', { method: 'POST', csrf: true, body: new URLSearchParams({username,password: config.RELAYOPS_DEMO_PASSWORD}) });
    if (response.status === 204) csrf = await (await request('/auth/csrf')).json();
    return response;
  };
  return { request, login };
}
const owner = client();
assert.equal((await owner.request('/api/v1/me')).status, 401);
assert.equal((await owner.request('/',{method:'POST'})).status,403);
const index = await owner.request('/'); assert.equal(index.status,200);
const html = await index.text(); assert.ok(!html.includes('/src/main.ts'));
const asset = html.match(/src="(\/assets\/[^\"]+\.js)"/)[1]; assert.equal((await owner.request(asset)).status,200);
assert.equal((await owner.request('/fonts/ibm-plex-sans-400.ttf')).status,200);
const health = await owner.request('/healthz'); assert.equal(health.status,200); assert.equal(health.headers.get('cache-control'),'no-store');
assert.deepEqual(await health.json(),{status:'UP',version:'1.0.0'});
assert.equal((await owner.login('owner.a')).status,401,'release profile must not create demo users');
assert.equal((await owner.login('release.owner')).status,204);
const me = await (await owner.request('/api/v1/me')).json(); assert.equal(me.workspaces.length,1);
const space = me.workspaces[0], prefix = `/api/v1/workspaces/${space.id}`;
assert.equal((await owner.request(`/api/v1/organizations/${space.tenantId}/audit-events?limit=invalid`)).status,422);
assert.equal((await owner.request('/assets/missing-asset.js')).status,404);
assert.equal((await owner.request(prefix+'/jobs',{method:'POST',body:'{}'})).status,403);
const profile = await (await owner.request(prefix+'/model-profile')).json(); assert.equal(profile.billable,false);
const applications = await (await owner.request(prefix+'/applications')).json();
const key = randomUUID(), body = { applicationId:applications[0].id,title:'发布包验收',inputText:'本次发布通过单机同源JAR提供工作台。\n\n模型调用必须保留引用和独立责任。',destinationIds:[],outputLanguage:'zh-CN' };
const create = () => owner.request(prefix+'/jobs',{method:'POST',csrf:true,headers:{'Content-Type':'application/json','Idempotency-Key':key},body:JSON.stringify(body)});
const accepted = await create(); assert.equal(accepted.status,202); const job = await accepted.json();
assert.equal((await (await create()).json()).jobId,job.jobId);
let metadata;
for(let i=0;i<80;i++) { metadata=await (await owner.request(prefix+'/jobs/'+job.jobId)).json();if(metadata.status==='SUCCEEDED')break;await new Promise(r=>setTimeout(r,200)); }
assert.equal(metadata.status,'SUCCEEDED'); assert.equal(metadata.modelCalls,1);
assert.equal((await owner.request(prefix+'/jobs/'+job.jobId+'/result')).status,200);
const member = client(); assert.equal((await member.login('release.member')).status,204);
assert.equal((await member.request(prefix+'/jobs/'+job.jobId)).status,404);
assert.equal((await member.request(`/api/v1/organizations/${space.tenantId}/audit-events`)).status,403);
console.log(JSON.stringify({version:'1.0.0',sameOriginJar:true,releaseProfileNoDemo:true,provisionedOwnerAndMember:true,anonymousAndCsrf:true,invalidQuery422:true,fakeModelCalls:1,memberIsolation:true}));
