import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { openProfile } from "../../app/actions";
import { getState, resetStore } from "../../app/store";
import { ProfileView } from "./ProfileView";

beforeEach(() => {
  resetStore();
  vi.spyOn(window, "scrollTo").mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("ProfileView", () => {
  it("paints the identity and the universe numbers from the sketch", () => {
    openProfile();
    render(<ProfileView hidden={false} />);

    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("John");
    expect(screen.getByText("Member since March, 2024")).toBeTruthy();
    expect(screen.getByText("My Entertainment Universe")).toBeTruthy();
    expect(screen.getByText("230")).toBeTruthy();
    expect(screen.getByText("12")).toBeTruthy();
    expect(screen.getByText("1,034")).toBeTruthy();
  });

  it("shows four collection tiles with disabled preview links", () => {
    openProfile();
    const { container } = render(<ProfileView hidden={false} />);

    expect(container.querySelectorAll(".profile-collection")).toHaveLength(4);
    expect(screen.getByText("Movies Collection")).toBeTruthy();
    expect(screen.getByText("Music Collection")).toBeTruthy();
    expect(screen.getByText("TV Shows Collection")).toBeTruthy();
    expect(screen.getByText("Books Collection")).toBeTruthy();
    expect(screen.getByText("112 items")).toBeTruthy();

    const links = screen.getAllByRole("button", { name: /view collection/i });
    expect(links).toHaveLength(4);
    expect(links.every((link) => (link as HTMLButtonElement).disabled)).toBe(true);
  });

  it("edits the display name and member line without leaving the page", () => {
    openProfile();
    render(<ProfileView hidden={false} />);

    fireEvent.click(screen.getByRole("button", { name: /edit profile/i }));
    expect(screen.getByRole("form", { name: "Edit profile" })).toBeTruthy();

    fireEvent.change(screen.getByLabelText("Display name"), {
      target: { value: "Vincent" },
    });
    fireEvent.change(screen.getByLabelText("Member since"), {
      target: { value: "April, 2024" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^save$/i }));

    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Vincent");
    expect(screen.getByText("Member since April, 2024")).toBeTruthy();
    expect(getState().profile).toMatchObject({
      name: "Vincent",
      memberSince: "April, 2024",
      editing: false,
    });
  });

  it("cancels an edit and keeps the stored identity", () => {
    openProfile();
    render(<ProfileView hidden={false} />);

    fireEvent.click(screen.getByRole("button", { name: /edit profile/i }));
    fireEvent.change(screen.getByLabelText("Display name"), {
      target: { value: "Vincent" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^cancel$/i }));

    expect(screen.queryByRole("form", { name: "Edit profile" })).toBeNull();
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("John");
    expect(getState().profile.name).toBe("John");
  });

  it("closes back to the view it covered", () => {
    openProfile();
    render(<ProfileView hidden={false} />);

    fireEvent.click(screen.getByRole("button", { name: /back/i }));
    expect(getState().view).toBe("browse");
  });

  it("stays in the DOM but hidden when another view is showing", () => {
    openProfile();
    const { container } = render(<ProfileView hidden />);

    expect(container.querySelector("#profileView")?.classList.contains("hidden")).toBe(
      true
    );
    expect(screen.getByText("Movies Collection")).toBeTruthy();
  });
});
