import { useEffect } from "react";
import { backToBrowse, closeSettings, escapeProfile, openSettings } from "./actions";
import { switchDomain } from "./navigation";
import { useStore } from "./store";
import { useArtwork } from "../hooks/useArtwork";
import { useContent } from "../hooks/useContent";
import { useDetail } from "../hooks/useDetail";
import { useEscape } from "../hooks/useEscape";
import { useNarrowSidebar } from "../hooks/useNarrowSidebar";
import { Sidebar } from "../components/shell/Sidebar";
import { Header } from "../components/shell/Header";
import { BrowseView } from "../components/browse/BrowseView";
import { SettingsView } from "../components/settings/SettingsView";
import { ProfileView } from "../components/profile/ProfileView";
import { MovieDetailView } from "../components/detail/movie/MovieDetailView";
import { SongDetailView } from "../components/detail/song/SongDetailView";
import { BookDetailView } from "../components/detail/book/BookDetailView";
import { GameDetailView } from "../components/detail/game/GameDetailView";
import { PlayerBar } from "../components/detail/song/PlayerBar";
import { cn } from "../lib/cn";

export function App() {
  const view = useStore((state) => state.view);
  const domain = useStore((state) => state.activeDomain);
  const { collapsed, toggle } = useNarrowSidebar();

  useArtwork();
  useContent(domain);
  useDetail(domain);

  useEscape(closeSettings, view === "settings");
  // Profile's Escape is two-stage: close the editor, then the page.
  useEscape(escapeProfile, view === "profile");

  // Escape leaves a detail. The game lightbox is an overlay on top of it, so it
  // gets the key first (it marks itself open with data-lightbox-open).
  useEffect(() => {
    if (view !== "detail") return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (document.querySelector('[data-lightbox-open="true"]')) return;
      backToBrowse(domain);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [view, domain]);

  return (
    <>
      <div className="sky" aria-hidden="true" />
      <div className="veil" aria-hidden="true" />

      {/* data-sidebar is the grid's hook: the card ladder reads it to give the
          collapsed rail's room back as a sixth card (base.css). */}
      <div className="app-shell" data-sidebar={collapsed ? "collapsed" : "open"}>
        <Sidebar
          collapsed={collapsed}
          onToggle={toggle}
          activeDomain={domain}
          view={view}
          onSelect={switchDomain}
          onSettings={openSettings}
        />

        <div className="main-column">
          <Header domain={domain} />

          <BrowseView hidden={view !== "browse"} />

          <SettingsView hidden={view !== "settings"} />

          <ProfileView hidden={view !== "profile"} />

          <section
            className={cn(
              "movie-view",
              !(view === "detail" && domain === "movie") && "hidden"
            )}
            id="movieView"
            aria-live="polite"
          >
            <MovieDetailView />
          </section>

          <section
            className={cn("book-view", !(view === "detail" && domain === "book") && "hidden")}
            id="bookView"
            aria-live="polite"
          >
            <BookDetailView />
          </section>

          <section
            className={cn("song-view", !(view === "detail" && domain === "music") && "hidden")}
            id="songView"
            aria-live="polite"
          >
            <SongDetailView />
          </section>

          <section
            className={cn("game-view", !(view === "detail" && domain === "game") && "hidden")}
            id="gameView"
            aria-live="polite"
          >
            <GameDetailView />
          </section>

          <PlayerBar />
        </div>
      </div>
    </>
  );
}
