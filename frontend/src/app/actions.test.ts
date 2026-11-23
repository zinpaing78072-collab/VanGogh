import { beforeEach, describe, expect, it } from "vitest";
import {
  activateDomain,
  activeSession,
  backToBrowse,
  browseTitle,
  closeSettings,
  failBrowse,
  failDetail,
  finishBrowse,
  finishDetail,
  openDetail,
  openSettings,
  rememberView,
  saveScroll,
  setBrowseArtwork,
  setDetailArt,
  startBrowse,
  toggleAi,
} from "./actions";
import { getState, resetStore } from "./store";

beforeEach(() => resetStore());

describe("session switching", () => {
  it("starts on the movie form with every session idle", () => {
    const state = getState();
    expect(state.activeDomain).toBe("movie");
    expect(state.view).toBe("browse");
    expect(state.ai).toBe(false);
    expect(Object.values(state.sessions).map((session) => session.browse.status)).toEqual([
      "idle",
      "idle",
      "idle",
      "idle",
    ]);
  });

  it("keeps each form's cards when the user leaves it", () => {
    finishBrowse("movie", [{ id: 1 }]);
    finishBrowse("book", [{ workKey: "OL1W" }]);

    activateDomain("book");
    activateDomain("movie");

    expect(getState().sessions.movie.browse.items).toHaveLength(1);
    expect(getState().sessions.book.browse.items).toHaveLength(1);
  });

  it("reopens the detail a form was showing", () => {
    openDetail("game", { id: 7 });
    finishDetail("game", { id: 7 });
    activateDomain("movie");

    activateDomain("game");

    expect(getState().view).toBe("detail");
  });

  it("ignores a click on the form already showing", () => {
    const before = getState();
    activateDomain("movie");
    expect(getState()).toBe(before);
  });

  it("leaves Settings when the user picks a form", () => {
    openSettings();
    activateDomain("book");
    expect(getState().view).toBe("browse");
    expect(getState().activeDomain).toBe("book");
  });
});

describe("browse transitions", () => {
  it("titles a load by kind", () => {
    expect(browseTitle("movie", "trending")).toBe("Trending Movies");
    expect(browseTitle("book", "search")).toBe("Search Results");
    expect(browseTitle("game", "recommend")).toBe("AI Recommendations");
  });

  it("clears the previous cards, artwork and detail when a load starts", () => {
    finishBrowse("movie", [{ id: 1 }]);
    setBrowseArtwork("movie", "1", "data:image/png;base64,AAA");
    openDetail("movie", { id: 1 });

    startBrowse("movie", "search", "dune");

    const session = getState().sessions.movie;
    expect(session.browse).toMatchObject({
      status: "loading",
      title: "Search Results",
      items: [],
      query: "dune",
      kind: "search",
      artwork: {},
      error: null,
    });
    expect(session.detail).toBeNull();
    expect(session.view).toBe("browse");
    expect(getState().view).toBe("browse");
  });

  it("records a failure with no cards", () => {
    finishBrowse("music", [{ id: "1" }]);
    failBrowse("music", "network down");

    expect(getState().sessions.music.browse).toMatchObject({
      status: "error",
      items: [],
      error: "network down",
    });
  });

  it("drops artwork for a form the user already left", () => {
    activateDomain("book");
    setBrowseArtwork("movie", "1", "data:image/png;base64,AAA");
    expect(getState().sessions.movie.browse.artwork).toEqual({});

    setBrowseArtwork("book", "OL1W", "data:image/png;base64,BBB");
    expect(getState().sessions.book.browse.artwork).toEqual({
      "OL1W": "data:image/png;base64,BBB",
    });
  });

  it("ignores an empty artwork payload", () => {
    const before = getState();
    setBrowseArtwork("movie", "1", "");
    expect(getState()).toBe(before);
  });
});

describe("detail transitions", () => {
  it("opens a loading shell with a fresh nonce", () => {
    openDetail("book", { workKey: "OL1W" });

    const detail = getState().sessions.book.detail;
    expect(detail).toMatchObject({
      status: "loading",
      nonce: 1,
      item: { workKey: "OL1W" },
      detail: null,
      art: {},
      error: null,
    });
    expect(getState().view).toBe("detail");
  });

  it("bumps the nonce on every open so a retry re-fires", () => {
    openDetail("book", { workKey: "OL1W" });
    openDetail("book", { workKey: "OL1W" });
    expect(getState().sessions.book.detail?.nonce).toBe(2);
  });

  it("merges the reply artwork over the streamed slots", () => {
    openDetail("music", { id: "42" });
    setDetailArt("music", "art", "data:image/png;base64,STREAM");
    finishDetail("music", { id: "42" }, { art: "data:image/png;base64,REPLY" });

    const detail = getState().sessions.music.detail;
    expect(detail?.status).toBe("ready");
    expect(detail?.art).toEqual({ art: "data:image/png;base64,REPLY" });
    expect(detail?.nonce).toBe(1);
  });

  it("keeps the clicked item and artwork on failure", () => {
    openDetail("game", { id: 7, title: "Hades" });
    setDetailArt("game", "boxart", "data:image/png;base64,AAA");
    failDetail("game", "timeout");

    const detail = getState().sessions.game.detail;
    expect(detail).toMatchObject({
      status: "error",
      item: { id: 7, title: "Hades" },
      detail: null,
      error: "timeout",
      art: { boxart: "data:image/png;base64,AAA" },
    });
  });

  it("ignores a duplicate or empty slot", () => {
    openDetail("movie", { id: 1 });
    setDetailArt("movie", "poster", "data:image/png;base64,AAA");
    const afterFirst = getState();
    setDetailArt("movie", "poster", "data:image/png;base64,AAA");
    setDetailArt("movie", "still:0", "");
    expect(getState()).toBe(afterFirst);
  });

  it("returns to the grid without dropping the detail", () => {
    openDetail("movie", { id: 1 });
    finishDetail("movie", { id: 1 });
    backToBrowse("movie");

    expect(getState().view).toBe("browse");
    expect(getState().sessions.movie.view).toBe("browse");
    expect(getState().sessions.movie.detail?.status).toBe("ready");
  });
});

describe("settings round-trip", () => {
  it("returns to the detail it covered", () => {
    openDetail("movie", { id: 1 });
    openSettings();
    expect(getState().view).toBe("settings");
    expect(getState().settingsReturn).toBe("detail");

    closeSettings();
    expect(getState().view).toBe("detail");
  });

  it("returns to the grid when opened over browse", () => {
    openSettings();
    closeSettings();
    expect(getState().view).toBe("browse");
  });

  it("is a no-op to close when not open", () => {
    const before = getState();
    closeSettings();
    expect(getState()).toBe(before);
  });
});

describe("small transitions", () => {
  it("remembers a view and a scroll position", () => {
    rememberView("book", "detail");
    saveScroll("book", 640);

    const session = getState().sessions.book;
    expect(session.view).toBe("detail");
    expect(session.scrollY).toBe(640);
  });

  it("skips a no-op patch", () => {
    rememberView("book", "browse");
    const before = getState();
    rememberView("book", "browse");
    expect(getState()).toBe(before);
  });

  it("toggles the AI flag", () => {
    toggleAi();
    expect(getState().ai).toBe(true);
  });

  it("exposes the active session", () => {
    activateDomain("music");
    expect(activeSession()).toBe(getState().sessions.music);
  });
});
