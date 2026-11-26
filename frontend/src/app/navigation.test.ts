import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../hooks/useContent", () => ({
  loadContent: vi.fn(),
  resumePosters: vi.fn(),
}));

import { loadContent } from "../hooks/useContent";
import { resetStore, setState } from "./store";
import { switchDomain } from "./navigation";

beforeEach(() => {
  resetStore();
  vi.clearAllMocks();
});

describe("domain switching", () => {
  it("refreshes Genius trending when returning to the Music form", () => {
    setState((state) => ({
      ...state,
      sessions: {
        ...state.sessions,
        music: {
          ...state.sessions.music,
          browse: {
            ...state.sessions.music.browse,
            status: "ready",
            kind: "trending",
          },
        },
      },
    }));

    switchDomain("music");

    expect(loadContent).toHaveBeenCalledWith("music", "trending", "");
  });
});
