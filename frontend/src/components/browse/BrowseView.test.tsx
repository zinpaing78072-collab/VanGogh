import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { failBrowse, finishBrowse, startBrowse } from "../../app/actions";
import { getState, resetStore, setState } from "../../app/store";
import type { Book, Movie } from "../../bridge/types";
import { BrowseView } from "./BrowseView";

const MOVIES: Movie[] = [
  {
    id: 603,
    title: "The Matrix",
    year: 1999,
    rating: 8.2,
    genre: "Science Fiction",
    overview: "",
    posterUrl: "",
  },
  {
    id: 27205,
    title: "Inception",
    year: 2010,
    rating: 8.4,
    genre: "Action",
    overview: "",
    posterUrl: "",
  },
];

const BOOKS: Book[] = [
  {
    workKey: "OL45804W",
    title: "Dune",
    author: "Frank Herbert",
    year: 1965,
    rating: 4.1,
    subjects: "",
    firstSentence: "",
    pages: 412,
    editionCount: 3,
    coverUrl: "",
  },
];

beforeEach(() => {
  resetStore();
  vi.spyOn(window, "scrollTo").mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("BrowseView", () => {
  it("paints one card per item with the domain's card class", () => {
    finishBrowse("movie", MOVIES);
    const { container } = render(<BrowseView hidden={false} />);

    expect(screen.getByText("Trending Movies")).toBeTruthy();
    expect(container.querySelectorAll("#movieGrid .movie-card")).toHaveLength(2);
    expect(screen.getByText("Inception")).toBeTruthy();
  });

  it("shows eight skeletons while the load is in flight", () => {
    startBrowse("movie", "search", "dune");
    const { container } = render(<BrowseView hidden={false} />);

    expect(screen.getByText("Search Results")).toBeTruthy();
    expect(container.querySelectorAll(".skeleton-card")).toHaveLength(8);
  });

  it("shows the error text instead of cards", () => {
    failBrowse("movie", "network down");
    const { container } = render(<BrowseView hidden={false} />);

    expect(screen.getByText("network down")).toBeTruthy();
    expect(container.querySelectorAll(".movie-card")).toHaveLength(0);
  });

  it("names the empty state after the form", () => {
    finishBrowse("movie", []);
    render(<BrowseView hidden={false} />);

    expect(screen.getByText("No movies found.")).toBeTruthy();
  });

  it("renders book cards keyed by workKey", () => {
    setState((state) => ({ ...state, activeDomain: "book" }));
    finishBrowse("book", BOOKS);
    const { container } = render(<BrowseView hidden={false} />);

    const card = container.querySelector("#movieGrid .book-card");
    expect(card?.getAttribute("data-book-id")).toBe("OL45804W");
  });

  it("opens the clicked item's detail and keeps the grid below it", () => {
    finishBrowse("movie", MOVIES);
    const { container } = render(<BrowseView hidden={false} />);

    container.querySelector<HTMLElement>("#movieGrid .movie-card")?.click();

    const state = getState();
    expect(state.view).toBe("detail");
    expect(state.sessions.movie.detail?.item).toMatchObject({ id: 603 });
    expect(state.sessions.movie.browse.items).toHaveLength(2);
  });

  it("keeps the grid in the DOM when hidden", () => {
    finishBrowse("movie", MOVIES);
    const { container } = render(<BrowseView hidden />);

    expect(container.querySelector("#browseView")?.classList.contains("hidden")).toBe(true);
    expect(container.querySelectorAll("#movieGrid .movie-card")).toHaveLength(2);
  });
});
