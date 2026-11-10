import { pathToFileURL } from "node:url"

export async function runSmoke({ baseUrl = process.env.PERSONA_BACKEND_BASE_URL || "", timeoutMs = 10000 } = {}) {
  baseUrl = baseUrl.replace(/\/$/, "")
  if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://127.0.0.1")) {
    throw new Error("Set PERSONA_BACKEND_BASE_URL to HTTPS or 127.0.0.1")
  }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error("Invalid request timeout")
  const suffix = `${Date.now()}-${Math.random().toString(16).slice(2)}`
  const password = `Persona-${suffix}!`
  let userA, userB, primaryError
  try {
    userA = await register(`persona-a-${suffix}@example.test`, password)
    userB = await register(`persona-b-${suffix}@example.test`, password)
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
    return request("POST", "/api/auth/register", null, { email, password: passwordValue })
  }

  async function send(method, path, token, body, expected) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), timeoutMs)
    let statusError
    try {
      const response = await fetch(`${baseUrl}${path}`, {
        method,
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
