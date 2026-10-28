import type { ServerResponse } from "node:http"

export function onceDrain(response: ServerResponse, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      response.off("drain", drained)
      response.off("close", closed)
      response.off("error", failed)
      signal.removeEventListener("abort", aborted)
    }
    const drained = () => { cleanup(); resolve() }
    const failed = (error: Error) => { cleanup(); reject(error) }
    const closed = () => failed(new Error("downstream_closed"))
    const aborted = () => failed(signal.reason ?? new Error("chat_aborted"))
    response.once("drain", drained)
    response.once("close", closed)
    response.once("error", failed)
    signal.addEventListener("abort", aborted, { once: true })
    if (signal.aborted) aborted()
    else if (response.destroyed || response.writableEnded) closed()
  })
}

// Inspect bounded SSE frames while forwarding the original bytes unchanged.
export class ChatStream {
  private readonly decoder = new TextDecoder("utf-8", { fatal: true })
  private line = ""
  private data: string[] = []
  private frameChars = 0
  private afterCR = false
  done = false
  completionTokens: number | undefined

  push(chunk: Uint8Array): void {
    this.consume(this.decoder.decode(chunk, { stream: true }))
  }

  finish(): void {
    this.consume(this.decoder.decode())
    if (!this.done) throw new Error("incomplete_upstream_stream")
  }

  private consume(text: string): void {
    for (const char of text) {
      if (this.afterCR && char === "\n") { this.afterCR = false; continue }
      this.afterCR = char === "\r"
      if (char === "\r" || char === "\n") {
        this.consumeLine()
        this.line = ""
      } else {
        this.line += char
        if (++this.frameChars > 1_000_000) throw new Error("upstream_frame_too_large")
      }
    }
  }

  private consumeLine(): void {
    if (this.line !== "") {
      if (this.line.startsWith("data:")) this.data.push(this.line.slice(5).replace(/^ /, ""))
      return
    }
    this.frameChars = 0
    if (!this.data.length) return
    const data = this.data.join("\n")
    this.data = []
    if (this.done) throw new Error("unexpected_upstream_data")
    if (data === "[DONE]") { this.done = true; return }
    const event = JSON.parse(data)
    if (!event || !Array.isArray(event.choices) || event.error) throw new Error("invalid_upstream_event")
    if (event.usage != null) {
      const tokens: unknown = event.usage.completion_tokens
      if (typeof tokens !== "number" || !Number.isSafeInteger(tokens) || tokens < 0) {
        throw new Error("invalid_upstream_usage")
      }
      const terminal = event.choices.length === 0 || event.choices.some(
        (choice: { finish_reason?: unknown } | null) => choice?.finish_reason != null
      )
      if (terminal) this.completionTokens = Math.max(this.completionTokens ?? 0, tokens)
    }
  }
}
