import { beforeEach, describe, expect, it } from "vitest";
import {
  pollArt,
  pollBrowse,
  pollDetail,
  pollDetailArt,
  requestBrowse,
  requestDetail,
} from "./commands";
import type { Book, Game, Movie, Song } from "./types";

/**
 * The command names and payload keys below are the Java side's contract
 * (`@KremaCommand` methods and record parameter names). A rename here without
 * the matching Java change is a silent broken IPC call, so it is pinned.
 */

const calls: Array<{ command: string; args: unknown }> = [];

beforeEach(() => {
  calls.length = 0;
  window.krema = {
    invoke: async (command: string, args?: unknown) => {
      calls.push({ command, args });
      return 0;
    },
  };
});

describe("requestBrowse", () => {
  it("maps each form to its request command", async () => {
    await requestBrowse("movie", "trending", "");
    await requestBrowse("game", "search", "hades");
    await requestBrowse("music", "recommend", "rainy day");
    await requestBrowse("book", "search", "dune");

    expect(calls).toEqual([
      { command: "requestMovies", args: { kind: "trending", query: "" } },
      { command: "requestGames", args: { kind: "search", query: "hades" } },
      { command: "requestGeniusMusic", args: { kind: "recommend", query: "rainy day" } },
      { command: "requestBooks", args: { kind: "search", query: "dune" } },
    ]);
  });
});

describe("pollBrowse", () => {
  it("maps each form to its data drain", async () => {
    await pollBrowse("movie", 1);
    await pollBrowse("game", 2);
    await pollBrowse("music", 3);
    await pollBrowse("book", 4);

    expect(calls.map((call) => call.command)).toEqual([
      "pollMovieData",
      "pollGameData",
      "pollGeniusMusicData",
      "pollBookData",
    ]);
    expect(calls.map((call) => call.args)).toEqual([
      { generation: 1 },
      { generation: 2 },
      { generation: 3 },
      { generation: 4 },
    ]);
  });
});

describe("pollArt", () => {
  it("maps each form to its artwork drain", async () => {
    await pollArt("movie");
    await pollArt("game");
    await pollArt("music");
    await pollArt("book");

    expect(calls.map((call) => call.command)).toEqual([
      "pollMoviePosters",
      "pollGamePosters",
      "pollGeniusMusicPosters",
      "pollBookCovers",
    ]);
  });
});

describe("requestDetail", () => {
  it("sends the identity field each Java handler expects", async () => {
    await requestDetail("movie", { id: 603 } as Movie);
    await requestDetail("game", { id: 42 } as Game);
    await requestDetail("music", { id: "7788" } as Song);
    await requestDetail("book", { workKey: "OL45804W" } as Book);

    expect(calls).toEqual([
      { command: "requestMovieDetail", args: { id: 603 } },
      { command: "requestGameDetail", args: { id: 42 } },
      { command: "requestSongDetail", args: { id: "7788" } },
      { command: "requestBookDetail", args: { workKey: "OL45804W" } },
    ]);
  });

  it("coerces a string id for the numeric handlers", async () => {
    await requestDetail("movie", { id: "603" } as unknown as Movie);
    expect(calls[0]?.args).toEqual({ id: 603 });
  });
});

describe("pollDetail", () => {
  it("maps each form to its detail drain", async () => {
    await pollDetail("movie", 1);
    await pollDetail("game", 2);
    await pollDetail("music", 3);
    await pollDetail("book", 4);

    expect(calls.map((call) => call.command)).toEqual([
      "pollMovieDetail",
      "pollGameDetail",
      "pollSongDetail",
      "pollBookDetail",
    ]);
  });
});

describe("pollDetailArt", () => {
  it("drains artwork for three forms and resolves -1 for songs", async () => {
    await pollDetailArt("movie");
    await pollDetailArt("game");
    await pollDetailArt("book");
    await expect(pollDetailArt("music")).resolves.toBe(-1);

    expect(calls.map((call) => call.command)).toEqual([
      "pollMovieDetailArt",
      "pollGameDetailArt",
      "pollBookDetailArt",
    ]);
  });
});
