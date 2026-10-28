import test from "node:test"
import assert from "node:assert/strict"
import { EventEmitter } from "node:events"
import type { ServerResponse } from "node:http"
import { setTimeout as delay } from "node:timers/promises"
import { ChatStream, onceDrain } from "../src/chat-stream.js"

for (const event of ["drain", "close", "error", "abort", "already-aborted", "already-closed"]) {
  test(`backpressure wait settles on ${event} and removes its listeners`, async () => {
    const emitter = Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false })
    const ignoreError = () => undefined
    emitter.on("error", ignoreError)
    const controller = new AbortController()
    if (event === "already-aborted") controller.abort()
    if (event === "already-closed") emitter.destroyed = true
    const pending = onceDrain(emitter as unknown as ServerResponse, controller.signal)
      .then(() => "resolved", () => "rejected")
    if (event === "abort") controller.abort()
    else if (!event.startsWith("already")) emitter.emit(event, new Error("test failure"))
    assert.equal(await Promise.race([pending, delay(100, "still waiting")]), event === "drain" ? "resolved" : "rejected")
    assert.equal(emitter.listenerCount("drain"), 0)
    assert.equal(emitter.listenerCount("close"), 0)
    assert.equal(emitter.listenerCount("error"), 1)
    emitter.off("error", ignoreError)
  })
}

test("SSE parser accepts UTF-8, split CRLF, multiline data, comments and repeated terminal usage", () => {
  const stream = new ChatStream()
  const data = ': keepalive\r\ndata: {"choices":[{"delta":{"content":"你好"}}],\r\ndata: "usage":null}\r\n\r\n' +
    'data: {"choices":[],"usage":{"completion_tokens":3}}\r\n\r\n' +
    'data: {"choices":[{"finish_reason":"stop"}],"usage":{"completion_tokens":3}}\r\n\r\ndata: [DONE]\r\n\r\n'
  for (const byte of new TextEncoder().encode(data)) stream.push(Uint8Array.of(byte))
  stream.finish()
  assert.equal(stream.done, true)
  assert.equal(stream.completionTokens, 3)
})

test("SSE parser bounds frames, rejects malformed data, and requires DONE", () => {
  for (const data of [
    'data: nope\n\n', 'data: {"error":"private"}\n\n',
    'data: {"choices":[],"usage":{"completion_tokens":-1}}\n\n',
    ': ' + 'x'.repeat(1_000_000)
  ]) assert.throws(() => new ChatStream().push(new TextEncoder().encode(data)))
  const stream = new ChatStream()
  stream.push(new TextEncoder().encode('data: {"choices":[]}\n\n'))
  assert.throws(() => stream.finish(), /incomplete/)
})
