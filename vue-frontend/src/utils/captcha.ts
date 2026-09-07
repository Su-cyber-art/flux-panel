/** Adapt the Tianai 1.5 API envelope to the bundled renderer's legacy shape. */
export function normalizeCaptchaResponse(response: any) {
  if (!response || (response.code != null && Number(response.code) !== 200)) {
    throw new Error(response?.msg || '生成验证码失败，请重试')
  }
  const data = response.data ?? response
  const captcha = data.captcha ?? data
  const id = data.id
  if (typeof id !== 'string' || !id ||
      !['SLIDER', 'ROTATE', 'CONCAT', 'WORD_IMAGE_CLICK'].includes(captcha.type) ||
      typeof captcha.backgroundImage !== 'string' || !captcha.backgroundImage) {
    throw new Error('验证码响应格式不正确，请检查服务版本')
  }
  return { id, captcha }
}

/** Tianai 1.5.5 uses Long timestamps; JSON.stringify(Date) would send ISO strings. */
export function normalizeCaptchaTrack(track: unknown) {
  if (!track || typeof track !== 'object' || Array.isArray(track)) {
    throw new Error('验证码轨迹数据异常，请重新验证')
  }
  const result = { ...(track as Record<string, unknown>) }
  for (const field of ['startTime', 'stopTime']) {
    const value = result[field]
    const timestamp = value instanceof Date ? value.getTime() : value
    if (typeof timestamp !== 'number' || !Number.isSafeInteger(timestamp) || timestamp < 0) {
      throw new Error('验证码时间数据异常，请重新验证')
    }
    result[field] = timestamp
  }
  return result
}

export async function requestCaptchaJson(url: string, data: unknown, signal: AbortSignal, timeoutMs = 15000) {
  if (signal.aborted) throw new DOMException('Cancelled', 'AbortError')
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal.addEventListener('abort', abort, { once: true })
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: typeof data === 'string' ? data : JSON.stringify(data),
      signal: controller.signal,
    })
    if (!response.ok) throw new Error(`验证码接口请求失败（HTTP ${response.status}）`)
    return await response.json()
  } catch (error) {
    if (timedOut) throw new Error('验证码请求超时，请重试')
    throw error
  } finally {
    clearTimeout(timer)
    signal.removeEventListener('abort', abort)
  }
}

/** Own the legacy SDK's async lifecycle; a closed/refreshed challenge must not finish a login. */
export function manageCaptcha(tac: any, onError: (error: unknown) => void) {
  let disposed = false
  let generation = 0
  let controller = new AbortController()
  let verifying = false
  const current = (value: number) => !disposed && value === generation
  const create = tac.createCaptcha.bind(tac)
  const report = (error: unknown, value: number) => {
    if (current(value)) onError(error)
  }

  tac.config.requestCaptchaData = async () => {
    const value = generation
    const response = await requestCaptchaJson(tac.config.requestCaptchaDataUrl, {}, controller.signal)
    if (!current(value)) throw new DOMException('Cancelled', 'AbortError')
    return normalizeCaptchaResponse(response)
  }
  tac.config.validCaptcha = async (id: string, track: unknown, captcha: any) => {
    if (disposed || verifying) return
    const value = generation
    verifying = true
    try {
      const response = await requestCaptchaJson(tac.config.validCaptchaUrl, { id, data: normalizeCaptchaTrack(track) }, controller.signal)
      if (!current(value)) return
      if (Number(response?.code) === 200) {
        if (typeof response?.data?.validToken !== 'string' || !response.data.validToken) {
          throw new Error('验证码校验响应缺少凭证，请重试')
        }
        captcha.showTips('验证成功', 1, () => {
          if (current(value)) tac.config.validSuccess(response, captcha, tac)
        })
      } else {
        captcha.showTips(response?.msg || '验证失败，请重新尝试', 0, () => {
          if (current(value)) tac.config.validFail(response, captcha, tac)
        })
      }
    } catch (error) {
      report(error, value)
    } finally {
      if (current(value)) verifying = false
    }
  }
  // Avoid the SDK's delayed global destroy callback racing with a new challenge.
  tac.reloadCaptcha = () => {
    if (disposed) return
    controller.abort()
    controller = new AbortController()
    const value = ++generation
    verifying = false
    window.currentCaptcha?.destroy()
    window.currentCaptcha = undefined
    tac.showLoading()
    void Promise.resolve().then(() => {
      if (current(value)) return create()
    }).catch(error => report(error, value))
  }

  return {
    init: () => { if (!disposed) tac.init() },
    reload: () => tac.reloadCaptcha(),
    destroy: () => {
      if (disposed) return
      disposed = true
      generation++
      controller.abort()
      window.currentCaptcha?.destroy()
      tac.destroyWindow()
    },
  }
}
