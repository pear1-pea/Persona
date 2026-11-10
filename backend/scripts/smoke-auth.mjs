import { pathToFileURL } from "node:url"

export async function runSmoke({ baseUrl = process.env.PERSONA_BACKEND_BASE_URL || "", timeoutMs = 10000 } = {}) {
  let origin
  try { origin = new URL(baseUrl) } catch { throw new Error("Invalid smoke base URL") }
  if (origin.username || origin.password || origin.search || origin.hash ||
      !(origin.protocol === "https:" || (origin.protocol === "http:" && origin.hostname === "127.0.0.1"))) {
    throw new Error("Invalid smoke base URL: require HTTPS or loopback HTTP without credentials")
  }
  baseUrl = origin.href.replace(/\/$/, "")
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error("Invalid request timeout")
  const suffix = `${Date.now()}-${Math.random().toString(16).slice(2)}`
  const password = `Persona-${suffix}!`
  let userA, userB, primaryError
  try {
    userA = await register(`persona-a-${suffix}@example.test`, password)
    userB = await register(`persona-b-${suffix}@example.test`, password)
    if (userA.token === userB.token) throw new Error("Registration returned duplicate token")
    const publicPersona = await request("POST", "/api/personas", userA.token, {
      name: "Public A",
      traits: ["Public"],
      backstory: "Visible to everyone",
      isPublic: true
    })
    const privatePersona = await request("POST", "/api/personas", userA.token, {
      name: "Private A",
      traits: ["Private"],
      backstory: "Visible only to A",
      isPublic: false
    })

    await expectStatus("GET", `/api/personas/${publicPersona.id}`, userB.token, 200)
    await expectStatus("GET", `/api/personas/${privatePersona.id}`, userB.token, 404)
    await expectStatus("DELETE", `/api/personas/${publicPersona.id}`, userB.token, 404)
    await expectStatus("DELETE", `/api/personas/${publicPersona.id}`, userA.token, 204)
    await expectStatus("GET", "/api/auth/sessions", userA.token, 200)
  } catch (error) {
    primaryError = error
    throw error
  } finally {
    const failures = []
    for (const user of [userA, userB]) {
      if (!user) continue
      try {
        await expectStatus("DELETE", "/api/auth/account", user.token, 204, { currentPassword: password })
      } catch (error) {
        failures.push(error)
      }
    }
    if (failures.length) {
      if (primaryError) primaryError.cleanupErrors = failures
      else throw new AggregateError(failures, "Account cleanup failed")
    }
  }

  console.log("Auth and Persona isolation smoke test passed")

  async function register(email, passwordValue) {
    const user = await request("POST", "/api/auth/register", null, { email, password: passwordValue })
    if (!user || typeof user.token !== "string" || !/^[A-Za-z0-9._~+/-]+=*$/.test(user.token)) {
      throw new Error("Invalid registration token")
    }
    return user
  }

  async function send(method, path, token, body, expected) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    let statusError
    try {
      const response = await fetch(`${baseUrl}${path}`, {
        method,
        redirect: "error",
        signal: controller.signal,
        headers: {
          ...(token ? { authorization: `Bearer ${token}` } : {}),
          ...(body ? { "content-type": "application/json" } : {})
        },
        body: body ? JSON.stringify(body) : undefined
      })
      if (expected !== undefined ? response.status !== expected : !response.ok) {
        statusError = new Error(`${method} ${path}: ${expected === undefined ? "failed" : `expected ${expected}, got`} ${response.status}`)
        await response.body?.cancel()
        throw statusError
      }
      if (expected !== undefined || response.status === 204) {
        await response.arrayBuffer()
        return null
      }
      return await response.json()
    } catch (error) {
      if (statusError) throw statusError
      throw new Error(`${method} ${path}: ${controller.signal.aborted ? "request timed out" : "request failed"}`)
    } finally {
      clearTimeout(timer)
    }
  }
  function request(method, path, token, body) {
    return send(method, path, token, body)
  }
  function expectStatus(method, path, token, expected, body) {
    return send(method, path, token, body, expected)
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await runSmoke()
}
