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
  const movie = item as Movie;
  const game = item as Game;
  const title = movie.title || game.game_title || "Untitled";
  const year = movie.year || (game.release_date ? game.release_date.slice(0, 4) : "–");
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
          {domain === "game" ? (
            // TheGamesDB carries an age rating, not a score, so the game card
            // shows that string and no star (the star would always read "–").
            game.ageRating ? <span className="card-meta-item">{game.ageRating}</span> : null
          ) : (
            <span className="card-meta-item card-rating">
              ★ {movie.rating ? movie.rating.toFixed(1) : "–"}
            </span>
          )}
        </div>
      </div>
    </CardShell>
  );
}
