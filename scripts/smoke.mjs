import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setTimeout } from 'node:timers/promises'

const config = Object.fromEntries(readFileSync(new URL('../ops/.env', import.meta.url), 'utf8').split(/\r?\n/).filter(l => l && !l.startsWith('#')).map(l => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]))
const base = process.env.RELAYOPS_BASE_URL ?? 'http://127.0.0.1:8080'
const password = process.env.RELAYOPS_DEMO_PASSWORD ?? config.RELAYOPS_DEMO_PASSWORD
function client() {
  const cookies = new Map()
  let csrf
  async function request(path, init = {}, expected = 200) {
    const headers = new Headers(init.headers)
    if (cookies.size) headers.set('Cookie', [...cookies].map(([k, v]) => `${k}=${v}`).join('; '))
    if (init.method && csrf) headers.set(csrf.headerName, csrf.token)
    const res = await fetch(base + path, { ...init, headers, redirect: 'manual', signal: AbortSignal.timeout(10000) })
    for (const cookie of res.headers.getSetCookie()) {
      const pair = cookie.split(';')[0], at = pair.indexOf('=')
      cookies.set(pair.slice(0, at), pair.slice(at + 1))
    }
    const text = await res.text()
    assert.equal(res.status, expected, `${init.method ?? 'GET'} ${path}: expected ${expected}, got ${res.status}: ${text.slice(0, 300)}`)
    return text ? JSON.parse(text) : undefined
  }
  return {
    request,
    async login(username) {
      csrf = await request('/auth/csrf')
      await request('/auth/login', { method: 'POST', body: new URLSearchParams({ username, password }) }, 204)
      csrf = await request('/auth/csrf')
      return await request('/api/v1/me')
    },
  }
}

const anonymous = client()
await anonymous.request('/api/v1/me', {}, 401)
await anonymous.request('/auth/login', { method: 'POST', body: new URLSearchParams({ username: 'owner.a', password }) }, 403)
console.log('PASS: anonymous and CSRF rejection')

const owner = client(), me = await owner.login('owner.a')
const workspace = me.workspaces[0], path = `/api/v1/workspaces/${workspace.id}`
const [app] = await owner.request(path + '/applications')
const key = crypto.randomUUID()
const intent = { applicationId: app.id, title: '端到端可靠性约束', inputText: '只有事务提交后才能返回已受理。\n\n结果回调失败不会重新执行模型任务。', destinationIds: [], outputLanguage: 'zh-CN' }
const create = (body = intent) => ({ method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(body) })
const first = await owner.request(path + '/jobs', create(), 202)
assert.equal(first.status, 'QUEUED')
const replay = await owner.request(path + '/jobs', create(), 202)
assert.deepEqual(replay, first)
await owner.request(path + '/jobs', create({ ...intent, title: '不同意图' }), 409)
console.log(`PASS: accepted and idempotent replay jobId=${first.jobId}`)

let job
for (let i = 0; i < 30; i++) {
  job = await owner.request(path + '/jobs/' + first.jobId)
  if (['SUCCEEDED', 'FAILED', 'UNKNOWN'].includes(job.status)) break
  await setTimeout(1000)
}
assert.equal(job.status, 'SUCCEEDED')
assert.equal(job.modelCalls, 1)
const result = await owner.request(path + '/jobs/' + first.jobId + '/result')
assert.equal(result.data.modelProfile, 'fake-extract-v1')
assert.equal(result.data.summary.evidence[0].paragraphId, 'p0001')
assert.ok(result.paragraphs[0].text.includes(result.data.summary.evidence[0].quote))
console.log('PASS: persisted result and exact evidence, one SENT attempt')

const viewer = client(); await viewer.login('viewer.a')
await viewer.request(path + '/jobs/' + first.jobId)
await viewer.request(path + '/jobs/' + first.jobId + '/result', {}, 403)
await viewer.request(path + '/jobs', create(), 403)
const other = client(); await other.login('owner.b')
await other.request(path + '/jobs/' + first.jobId, {}, 404)
await other.request(path + '/jobs/' + first.jobId + '/result', {}, 404)
console.log('PASS: VIEWER and cross-workspace denial')
await owner.request('/auth/logout', { method: 'POST' }, 204)
await owner.request('/api/v1/me', {}, 401)
console.log('PASS: logout invalidates session')
console.log('SMOKE PASSED (real MySQL, fake model, no webhook)')
