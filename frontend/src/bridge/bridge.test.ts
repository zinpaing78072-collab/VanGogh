import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { bridgeReady, invoke, on } from "./bridge";

type Emit = (event: string, handler: (data: unknown) => void) => void;

function installBridge(invokeImpl: (command: string, args?: unknown) => Promise<unknown>) {
  const handlers = new Map<string, (data: unknown) => void>();
  const emit: Emit = (event, handler) => handlers.set(event, handler);
  const invokeSpy = vi.fn(invokeImpl);
  window.krema = { invoke: invokeSpy, on: emit };
  return { invokeSpy, handlers };
}

beforeEach(() => {
  delete window.krema;
});

afterEach(() => {
  delete window.krema;
  vi.restoreAllMocks();
});

describe("bridgeReady", () => {
  it("is false without a bridge", () => {
    expect(bridgeReady()).toBe(false);
  });

  it("is false for a shim with no invoke", () => {
    window.krema = {} as never;
    expect(bridgeReady()).toBe(false);
  });

  it("is true once the shell injected invoke", () => {
    installBridge(async () => 0);
    expect(bridgeReady()).toBe(true);
  });
});

describe("invoke", () => {
  it("passes the command and args through untouched", async () => {
    const { invokeSpy } = installBridge(async () => 7);

    await expect(invoke("requestMovies", { kind: "search", query: "dune" })).resolves.toBe(7);
    expect(invokeSpy).toHaveBeenCalledWith("requestMovies", {
      kind: "search",
      query: "dune",
    });
  });

  it("rejects with the page's own error text when the bridge is missing", async () => {
    await expect(invoke("pollMovieData", { generation: 1 })).rejects.toThrow(
      "API bridge not ready yet. Please wait a moment and try again."
    );
  });
});

describe("on", () => {
  it("unwraps Krema's { payload, timestamp } envelope", () => {
    const { handlers } = installBridge(async () => 0);
    const seen: unknown[] = [];
    on("poster", (payload) => seen.push(payload));

    handlers.get("poster")?.({ payload: { id: 1, dataUri: "data:image/png" }, timestamp: 9 });

    expect(seen).toEqual([{ id: 1, dataUri: "data:image/png" }]);
  });

  it("accepts a bare payload", () => {
    const { handlers } = installBridge(async () => 0);
    const seen: unknown[] = [];
    on("gamePoster", (payload) => seen.push(payload));

    handlers.get("gamePoster")?.({ id: 2, dataUri: "data:image/png" });

    expect(seen).toEqual([{ id: 2, dataUri: "data:image/png" }]);
  });

  it("drops an envelope with no payload", () => {
    const { handlers } = installBridge(async () => 0);
    const handler = vi.fn();
    on("bookCover", handler);

    handlers.get("bookCover")?.({ payload: null, timestamp: 1 });

    expect(handler).not.toHaveBeenCalled();
  });

  it("does nothing when the shim has no on()", () => {
    window.krema = { invoke: async () => 0 };
    expect(() => on("poster", () => undefined)).not.toThrow();
  });
});
