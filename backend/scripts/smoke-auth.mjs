const baseUrl = (process.env.PERSONA_BACKEND_BASE_URL || "").replace(/\/$/, "")
if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://127.0.0.1")) {
  throw new Error("Set PERSONA_BACKEND_BASE_URL to HTTPS or 127.0.0.1")
}

const suffix = `${Date.now()}-${Math.random().toString(16).slice(2)}`
const password = `Persona-${suffix}!`
const userA = await register(`persona-a-${suffix}@example.test`, password)
const userB = await register(`persona-b-${suffix}@example.test`, password)

try {
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
  console.log("Auth and Persona isolation smoke test passed")
} finally {
  await expectStatus("DELETE", "/api/auth/account", userA.token, 204, { currentPassword: password })
  await expectStatus("DELETE", "/api/auth/account", userB.token, 204, { currentPassword: password })
}

async function register(email, passwordValue) {
  return request("POST", "/api/auth/register", null, { email, password: passwordValue })
}

async function request(method, path, token, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body ? { "content-type": "application/json" } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  })
  if (!response.ok) throw new Error(`${method} ${path} failed: ${response.status}`)
  return response.status === 204 ? null : response.json()
}

async function expectStatus(method, path, token, expected, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body ? { "content-type": "application/json" } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  })
  if (response.status !== expected) {
    throw new Error(`${method} ${path}: expected ${expected}, got ${response.status}`)
  }
}
