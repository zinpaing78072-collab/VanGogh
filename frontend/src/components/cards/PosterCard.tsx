import type { Game, Movie } from "../../bridge/types";
import { CardArtwork, CardShell } from "./CardShell";
import { PosterPlaceholder } from "./Placeholder";

interface PosterCardProps {
  domain: "movie" | "game";
  item: Movie | Game;
  index: number;
  artwork?: string;
  onOpen: () => void;
}

/** The portrait-poster shape shared by movies and games. */
export function PosterCard({ domain, item, index, artwork, onOpen }: PosterCardProps) {
  const title =
    (item as Movie).title || (item as Game).game_title || "Untitled";
  const releaseDate = (item as Game).release_date || "";
  const year = (item as Movie).year || (releaseDate ? releaseDate.slice(0, 4) : "–");
  const rating = item.rating ? item.rating.toFixed(1) : "–";
  const genre = item.genre || "";

  return (
    <CardShell
      className={domain === "game" ? "game-card" : ""}
      index={index}
      identity={{ [`data-${domain}-id`]: String(item.id) }}
      onOpen={onOpen}
    >
      <CardArtwork
        className="card-poster"
        alt={`${title} poster`}
        dataUri={artwork}
        placeholder={<PosterPlaceholder />}
      />
      <div className="card-info">
        <div className="card-title">{title}</div>
        <div className="card-meta">
          <span className="card-meta-item">{year}</span>
          <span className="card-meta-item">{genre}</span>
          <span className="card-meta-item card-rating">★ {rating}</span>
        </div>
      </div>
    </CardShell>
  );
}
