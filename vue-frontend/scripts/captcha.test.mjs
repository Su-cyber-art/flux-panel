import assert from 'node:assert/strict'
import { test, afterEach } from 'node:test'
import fs from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const code = ts.transpileModule(fs.readFileSync(new URL('../src/utils/captcha.ts', import.meta.url), 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { normalizeCaptchaResponse, normalizeCaptchaTrack, requestCaptchaJson, manageCaptcha } = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'))
const originalFetch = globalThis.fetch
const originalWindow = globalThis.window
afterEach(() => { globalThis.fetch = originalFetch; globalThis.window = originalWindow })
const data = { id: 'challenge-id', type: 'SLIDER', backgroundImage: 'data:image/png;base64,fixture', templateImage: 'template', backgroundImageWidth: 600, backgroundImageHeight: 360, data: { randomY: 120 } }
const verifyFixture = JSON.parse(fs.readFileSync(new URL('../../springboot-backend/src/test/resources/captcha/verify-request.json', import.meta.url), 'utf8'))
const sdkTrack = () => ({ ...verifyFixture.data, startTime: new Date(verifyFixture.data.startTime), stopTime: new Date(verifyFixture.data.stopTime) })
const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
const tick = () => new Promise(resolve => setImmediate(resolve))
function fakeSdk() {
  globalThis.window = {}
  const calls = { rendered: [], errors: [], success: [], failure: [] }
  const sdk = {
    config: { requestCaptchaDataUrl: '/generate', validCaptchaUrl: '/verify', validSuccess: value => calls.success.push(value), validFail: value => calls.failure.push(value) },
    createCaptcha() { return this.config.requestCaptchaData().then(value => calls.rendered.push(value)) },
    init() { this.reloadCaptcha() }, showLoading() {}, destroyWindow() { window.currentCaptcha = undefined },
  }
  return { sdk, calls, session: manageCaptcha(sdk, error => calls.errors.push(error)) }
}

test('new backend envelope and all four captcha types preserve images, dimensions and extra data', () => {
  for (const type of ['SLIDER', 'ROTATE', 'CONCAT', 'WORD_IMAGE_CLICK']) {
    const payload = { ...data, type }
    assert.deepEqual(normalizeCaptchaResponse({ code: 200, msg: 'OK', data: payload }), { id: data.id, captcha: payload })
  }
  assert.deepEqual(normalizeCaptchaResponse({ id: data.id, captcha: data }), { id: data.id, captcha: data })
})
test('failed or incomplete generation responses never reach the renderer', () => {
  for (const response of [null, { code: 500, msg: '资源加载失败', data }, { code: 200, data: {} }, { code: 200, data: { ...data, type: 'UNKNOWN' } }]) assert.throws(() => normalizeCaptchaResponse(response))
})
test('bundled SDK exposes generation promise so async errors can be handled', async () => {
  const context = { window: {}, self: {}, document: { currentScript: { src: 'http://localhost/tac.js', tagName: 'SCRIPT' }, getElementsByTagName: () => [] }, console, setTimeout, clearTimeout }
  vm.runInNewContext(fs.readFileSync(new URL('../src/utils/tac.min.js', import.meta.url), 'utf8'), context)
  const prototype = context.window.TAC.prototype
  await assert.rejects(prototype.createCaptcha.call({ config: { requestCaptchaData: async () => { throw new Error('offline') } } }), /offline/)
  // Reproduce the protocol mismatch in the unadapted renderer with the actual new response.
  await assert.rejects(prototype.createCaptcha.call({ config: { requestCaptchaData: async () => ({ code: 200, data }) }, closeLoading() {} }), /type/)
})
test('HTTP errors and malformed JSON are surfaced', async () => {
  globalThis.fetch = async () => new Response('', { status: 403 })
  await assert.rejects(requestCaptchaJson('/generate', {}, new AbortController().signal), /HTTP 403/)
  globalThis.fetch = async () => new Response('<html>error</html>')
  await assert.rejects(requestCaptchaJson('/generate', {}, new AbortController().signal))
})
test('stalled requests time out and cancelled requests do not start', async () => {
  globalThis.fetch = (_, { signal }) => new Promise((_, reject) => signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))))
  await assert.rejects(requestCaptchaJson('/generate', {}, new AbortController().signal, 10), /超时/)
  const controller = new AbortController(); controller.abort()
  globalThis.fetch = () => { throw new Error('must not start') }
  await assert.rejects(requestCaptchaJson('/generate', {}, controller.signal), { name: 'AbortError' })
})
test('generation failure is recoverable and refresh renders the normalized response', async () => {
  const { calls, session } = fakeSdk()
  globalThis.fetch = async () => json({ code: 500, msg: '生成失败' })
  session.init(); await tick()
  assert.equal(calls.errors.length, 1); assert.equal(calls.rendered.length, 0)
  globalThis.fetch = async () => json({ code: 200, data })
  session.reload(); await tick()
  assert.deepEqual(calls.rendered[0], { id: data.id, captcha: data })
  session.destroy()
})
test('late generation responses after closing are ignored', async () => {
  const { calls, session } = fakeSdk()
  let finish
  globalThis.fetch = () => new Promise(resolve => { finish = resolve })
  session.init(); await tick(); session.destroy()
  finish(json({ code: 200, data })); await tick()
  assert.deepEqual(calls.rendered, []); assert.deepEqual(calls.errors, [])
})
test('verification retains the backend envelope and rejects success without a token', async () => {
  const { sdk, calls, session } = fakeSdk()
  const captcha = { showTips: (_, __, done) => done() }
  const response = { code: 200, data: { validToken: 'verified-token' } }
  globalThis.fetch = async (_, request) => { assert.deepEqual(JSON.parse(request.body), verifyFixture); return json(response) }
  await sdk.config.validCaptcha(verifyFixture.id, sdkTrack(), captcha)
  assert.deepEqual(calls.success, [response])
  globalThis.fetch = async () => json({ code: 200, data: {} })
  await sdk.config.validCaptcha(verifyFixture.id, sdkTrack(), captcha)
  assert.equal(calls.success.length, 1); assert.equal(calls.errors.length, 1)
  globalThis.fetch = async () => json({ code: 4001, msg: '验证失败' })
  await sdk.config.validCaptcha(verifyFixture.id, sdkTrack(), captcha)
  assert.equal(calls.failure.length, 1)
  session.destroy()
})
test('closing before a delayed success callback cannot trigger login', async () => {
  const { sdk, calls, session } = fakeSdk()
  let done
  globalThis.fetch = async () => json({ code: 200, data: { validToken: 'verified-token' } })
  await sdk.config.validCaptcha(verifyFixture.id, sdkTrack(), { showTips: (_, __, callback) => { done = callback } })
  session.destroy(); done()
  assert.equal(calls.success.length, 0)
})


test('Date timestamps serialize exactly as the Java DTO fixture without mutating SDK data', () => {
  const track = sdkTrack()
  const payload = { id: verifyFixture.id, data: normalizeCaptchaTrack(track) }
  assert.deepEqual(JSON.parse(JSON.stringify(payload)), verifyFixture)
  assert.ok(track.startTime instanceof Date)
  assert.ok(track.stopTime instanceof Date)
  assert.deepEqual(normalizeCaptchaTrack(verifyFixture.data), verifyFixture.data)
})
test('invalid or missing timestamps never reach the verification endpoint', async () => {
  const { sdk, calls, session } = fakeSdk()
  globalThis.fetch = () => { assert.fail('invalid time must not be submitted') }
  for (const value of [undefined, new Date(NaN), NaN, Infinity, 1.5, -1, '2024-03-09T16:00:00.000Z']) {
    await sdk.config.validCaptcha('id', { ...sdkTrack(), startTime: value }, {})
  }
  assert.equal(calls.errors.length, 7)
  assert.equal(calls.success.length, 0)
  session.destroy()
})
