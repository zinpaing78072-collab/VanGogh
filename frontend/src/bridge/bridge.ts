import type {
  CommandArgs,
  CommandName,
  CommandReply,
  EventMap,
  EventName,
} from "./types";

/**
 * The only module that touches window.krema.
 *
 * Krema's native bridge and krema-shim.js (JCEF) both expose
 * `invoke(command, args)` and `on(event, handler)`. Every command is a
 * synchronous UI-thread call on the Java side; the page never blocks, it
 * polls the drains.
 */

interface KremaBridge {
  invoke(command: string, args?: unknown): Promise<unknown>;
  on?(
    event: string,
    handler: (data: { payload?: unknown; timestamp?: number } | unknown) => void
  ): void;
}

declare global {
  interface Window {
    krema?: KremaBridge;
  }
}

export function bridgeReady(): boolean {
  return typeof window.krema?.invoke === "function";
}

export async function invoke<K extends CommandName>(
  command: K,
  args: CommandArgs<K>
): Promise<CommandReply<K>> {
  const bridge = window.krema;
  if (!bridge) {
    throw new Error("API bridge not ready yet. Please wait a moment and try again.");
  }
  return (await bridge.invoke(command, args)) as CommandReply<K>;
}

/**
 * Subscribe to one backend event. Krema has no unsubscribe, so subscriptions
 * live for the page's lifetime; handlers are cheap state writes.
 */
export function on<K extends EventName>(
  event: K,
  handler: (payload: EventMap[K]) => void
): void {
  const bridge = window.krema;
  if (!bridge || typeof bridge.on !== "function") return;
  bridge.on(event, (data) => {
    // Krema wraps payloads as { payload, timestamp }; the JCEF shim does the
    // same. Accept a bare payload too so tests can emit directly.
    const wrapped = data as { payload?: unknown } | null | undefined;
    const payload =
      wrapped && typeof wrapped === "object" && "payload" in wrapped
        ? wrapped.payload
        : wrapped;
    if (payload) handler(payload as EventMap[K]);
  });
}
