import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { setTimeout } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const config = Object.fromEntries(readFileSync(new URL('../ops/.env', import.meta.url), 'utf8').split(/\r?\n/).filter(line => line.includes('=')).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]))
const cookies = new Map()
let csrf
async function request(path, init = {}, expected = 200) {
  const headers = new Headers(init.headers)
  headers.set('Cookie', [...cookies].map(([k, v]) => `${k}=${v}`).join('; '))
  if (init.method && csrf) headers.set(csrf.headerName, csrf.token)
  const response = await fetch('http://127.0.0.1:8080' + path, { ...init, headers, signal: AbortSignal.timeout(10000) })
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(';')[0], at = pair.indexOf('=')
    cookies.set(pair.slice(0, at), pair.slice(at + 1))
  }
  assert.equal(response.status, expected, `${init.method ?? 'GET'} ${path}: ${response.status}`)
  return response.status === 204 ? undefined : await response.json()
}
function compose(...args) {
  execFileSync('docker.exe', ['compose', '--project-name', 'relayops', '-f', 'ops/compose.yml', '--env-file', 'ops/.env', ...args], { cwd: root, stdio: 'pipe', timeout: 60000 })
}

csrf = await request('/auth/csrf')
await request('/auth/login', { method: 'POST', body: new URLSearchParams({ username: 'operator.b', password: config.RELAYOPS_DEMO_PASSWORD }) }, 204)
csrf = await request('/auth/csrf')
const me = await request('/api/v1/me'), workspace = me.workspaces[0]
const path = `/api/v1/workspaces/${workspace.id}`
const [application] = await request(path + '/applications')
const key = crypto.randomUUID()
const init = { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify({ applicationId: application.id, title: 'Broker 停启验证', inputText: '消息 Broker 暂时不可用时，平台通过 Outbox 保留受理责任。', destinationIds: [], outputLanguage: 'zh-CN' }) }
let accepted
try {
  compose('stop', 'rabbitmq')
  accepted = await request(path + '/jobs', init, 202)
  await setTimeout(3000)
  const queued = await request(path + '/jobs/' + accepted.jobId)
  assert.equal(queued.status, 'QUEUED')
  assert.equal(queued.modelCalls, 0)
  assert.deepEqual(await request(path + '/jobs', init, 202), accepted)
  console.log(`PASS: Broker stopped, accepted job persisted without model call; replay unchanged jobId=${accepted.jobId}`)
} finally {
  // Always restore the specifically named service, including when an assertion fails.
  compose('start', '--wait', 'rabbitmq')
}
let job
for (let attempt = 0; attempt < 50; attempt++) {
  job = await request(path + '/jobs/' + accepted.jobId)
  if (job.status === 'SUCCEEDED') break
  await setTimeout(1000)
}
assert.equal(job.status, 'SUCCEEDED')
assert.equal(job.modelCalls, 1)
await request(path + '/jobs/' + accepted.jobId + '/result')
console.log('PASS: Broker recovered, Outbox delivered accepted work, exactly one SENT attempt')
console.log('BROKER FAULT SMOKE PASSED')
