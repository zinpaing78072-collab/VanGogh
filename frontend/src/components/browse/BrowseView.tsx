import type { CSSProperties } from "react";
import { openDetail } from "../../app/actions";
import { useStore } from "../../app/store";
import type { DomainKey } from "../../app/types";
import { DOMAINS } from "../../bridge/domains";
import type { Book, Game, Movie, Song } from "../../bridge/types";
import { cn } from "../../lib/cn";
import { BookCard } from "../cards/BookCard";
import { MusicCard } from "../cards/MusicCard";
import { PosterCard } from "../cards/PosterCard";

interface BrowseViewProps {
  hidden: boolean;
}

function open(domain: DomainKey, item: unknown): void {
  window.scrollTo(0, 0);
  openDetail(domain, item);
}

function SkeletonGrid({ domain }: { domain: DomainKey }) {
  const artClass = domain === "music" ? "card-cover" : "card-poster";
  return (
    <>
      {Array.from({ length: 8 }, (_, index) => (
        <div
          key={index}
          className="movie-card skeleton-card"
          style={{ "--i": index } as CSSProperties}
        >
          <div className={cn(artClass, "skeleton-poster")} />
          <div className="card-info">
            <div className="skeleton-line skeleton-title" />
            <div className="skeleton-line skeleton-meta" />
          </div>
        </div>
      ))}
    </>
  );
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className="empty-state">
      <p style={{ color: "#ff6b6b", margin: 0 }}>{message}</p>
    </div>
  );
}

export function BrowseView({ hidden }: BrowseViewProps) {
  const domain = useStore((state) => state.activeDomain);
  const browse = useStore((state) => state.sessions[state.activeDomain].browse);

  const cards = () => {
    switch (domain) {
      case "movie":
        return (browse.items as Movie[]).map((item, index) => (
          <PosterCard
            key={item.id}
            domain="movie"
            item={item}
            index={index}
            artwork={browse.artwork[String(item.id)]}
            onOpen={() => open(domain, item)}
          />
        ));
      case "game":
        return (browse.items as Game[]).map((item, index) => (
          <PosterCard
            key={item.id}
            domain="game"
            item={item}
            index={index}
            artwork={browse.artwork[String(item.id)]}
            onOpen={() => open(domain, item)}
          />
        ));
      case "music":
        return (browse.items as Song[]).map((item, index) => (
          <MusicCard
            key={item.id}
            item={item}
            index={index}
            artwork={browse.artwork[String(item.id)]}
            onOpen={() => open(domain, item)}
          />
        ));
      case "book":
        return (browse.items as Book[]).map((item, index) => (
          <BookCard
            key={item.workKey}
            item={item}
            index={index}
            artwork={browse.artwork[String(item.workKey)]}
            onOpen={() => open(domain, item)}
          />
        ));
    }
  };

  return (
    <main className={cn("container", hidden && "hidden")} id="browseView">
      <section className="trending-section">
        <h2 className="section-title" id="sectionTitle">
          {browse.title}
        </h2>
        <div className="card-grid" id="movieGrid">
          {browse.status === "loading" ? <SkeletonGrid domain={domain} /> : null}
          {browse.status === "error" ? (
            <EmptyState message={browse.error || "Failed to load content."} />
          ) : null}
          {browse.status === "ready" && browse.items.length === 0 ? (
            <EmptyState message={`No ${DOMAINS[domain].emptyLabel} found.`} />
          ) : null}
          {browse.status === "ready" ? cards() : null}
        </div>
      </section>
    </main>
  );
}
