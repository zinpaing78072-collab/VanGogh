import { useState, type ReactNode } from "react";
import { backToBrowse, openDetail } from "../../../app/actions";
import { useStore } from "../../../app/store";
import type { Movie, MovieDetail, SimilarMovie } from "../../../bridge/types";
import { cn } from "../../../lib/cn";
import { secureLink } from "../../../lib/format";

/**
 * Movie detail: the bento layout from prototypes/movie-detail-layouts/bento.html.
 *
 * Artwork arrives by slot through the movieDetailArt drain — poster, cast:<i>,
 * still:<i>, similar:<id> — and each slot keeps its placeholder until its data
 * URI lands, so a missing image never becomes a broken icon.
 */

const FILM_ICON = (
  <svg
    width="48"
    height="48"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <rect x="2" y="4" width="20" height="16" rx="2" />
    <path d="M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5" />
  </svg>
);

function initialsOf(name: string): string {
  const words = String(name || "")
    .split(/\s+/)
    .filter(Boolean);
  return (
    words
      .slice(0, 2)
      .map((word) => word.charAt(0).toUpperCase())
      .join("") || "?"
  );
}

function formatRuntime(minutes: number): string {
  const total = Math.max(0, Math.round(Number(minutes) || 0));
  if (!total) return "";
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (!hours) return rest + "m";
  return rest ? hours + "h " + rest + "m" : hours + "h";
}

function formatVotes(value: number): string {
  const n = Number(value) || 0;
  return n > 0 ? n.toLocaleString("en-US") : "";
}

/** One streamed artwork slot: image when it arrives, placeholder otherwise. */
function SlotArt({
  uri,
  alt,
  imageClass,
  placeholder,
  placeholderClass,
  children,
  placeholderTag = "div",
}: {
  uri: string;
  alt: string;
  imageClass: string;
  placeholder: ReactNode;
  placeholderClass?: string;
  children?: ReactNode;
  placeholderTag?: "div" | "span";
}) {
  const [failedUri, setFailedUri] = useState<string | null>(null);
  const show = Boolean(uri) && failedUri !== uri;

  const mark = placeholderTag === "span" ? (
    <span className={cn("poster-placeholder", placeholderClass, show && "hidden")}>
      {placeholder}
    </span>
  ) : (
    <div className={cn("poster-placeholder", placeholderClass, show && "hidden")}>
      {placeholder}
    </div>
  );

  return (
    <>
      <img
        className={cn(imageClass, !show && "hidden")}
        alt={alt}
        src={show ? uri : undefined}
        onError={() => setFailedUri(uri)}
      />
      {mark}
      {children}
    </>
  );
}

function BackRow() {
  return (
    <div className="movie-back-row">
      <button
        type="button"
        className="movie-back"
        data-action="back"
        onClick={() => backToBrowse("movie")}
      >
        <span aria-hidden="true">←</span> Back
      </button>
      <span className="movie-source-note">Data and artwork from TMDB</span>
    </div>
  );
}

function SkeletonLines({ count, step, start }: { count: number; step: number; start: number }) {
  return (
    <>
      {Array.from({ length: count }, (_, index) => (
        <div
          key={index}
          className="skeleton-line"
          style={{ width: start - index * step + "%" }}
        />
      ))}
    </>
  );
}

function LoadingMarkup({ movie }: { movie: Partial<Movie> }) {
  return (
    <>
      <BackRow />
      <div className="movie-bento">
        <div className="movie-tile t-poster">
          <div className="movie-poster-frame">
            <div className="poster-placeholder">{FILM_ICON}</div>
          </div>
        </div>
        <div className="movie-tile t-ident">
          <div className="movie-kicker">
            <span>Film</span>
            {movie.year ? <span className="chip">{String(movie.year)}</span> : null}
          </div>
          <h1 className="movie-title">{movie.title || "Untitled"}</h1>
          <p className="movie-loading-note">Loading film details…</p>
        </div>
        <div className="movie-tile t-facts">
          <div className="movie-panel-head">
            <h2>Details</h2>
            <span className="movie-panel-sub">TMDB</span>
          </div>
          <div className="movie-skeleton">
            <SkeletonLines count={6} step={8} start={78} />
          </div>
        </div>
        <section className="movie-tile t-story">
          <div className="movie-panel-head">
            <h2>Synopsis</h2>
            <span className="movie-panel-sub">TMDB overview</span>
          </div>
          <div className="movie-skeleton">
            <SkeletonLines count={4} step={9} start={94} />
          </div>
        </section>
      </div>
    </>
  );
}

function ErrorMarkup({ movie, error }: { movie: Partial<Movie>; error: string }) {
  return (
    <>
      <BackRow />
      <div className="movie-error">
        <h1 className="movie-title">{movie.title || "Untitled"}</h1>
        <p>Could not load this film&apos;s details: {error || "unknown error"}</p>
        <button
          type="button"
          className="movie-back"
          data-action="retry"
          onClick={() => openDetail("movie", movie)}
        >
          Try again
        </button>
      </div>
    </>
  );
}

function Identity({ detail }: { detail: MovieDetail }) {
  const [watchlist, setWatchlist] = useState(false);
  const [favorite, setFavorite] = useState(false);
  const runtime = formatRuntime(detail.runtimeMinutes);
  const chips = [detail.year, detail.certification, runtime].filter(Boolean);
  const trailer = secureLink(detail.trailerUrl);
  const tmdb = secureLink(detail.tmdbUrl);

  return (
    <>
      <div className="movie-kicker">
        <span>Film</span>
        {chips.map((value) => (
          <span key={String(value)} className="chip">
            {String(value)}
          </span>
        ))}
      </div>
      <h1 className="movie-title">{detail.title}</h1>
      {detail.tagline ? <p className="movie-tagline">{detail.tagline}</p> : null}

      {Number(detail.rating) > 0 ? (
        <div className="movie-score-row">
          <span className="movie-score">
            <span className="star" aria-hidden="true">
              ★
            </span>
            {Number(detail.rating).toFixed(1)}
            <span className="max">/10</span>
          </span>
          {formatVotes(detail.voteCount) ? (
            <span className="movie-votes">{formatVotes(detail.voteCount)} votes · TMDB</span>
          ) : null}
        </div>
      ) : null}

      {(detail.genres || []).length ? (
        <ul className="movie-genres">
          {(detail.genres || []).map((name) => (
            <li key={name}>{name}</li>
          ))}
        </ul>
      ) : null}

      <div className="movie-actions">
        {trailer ? (
          <a
            className="movie-action movie-action-primary"
            href={trailer}
            target="_blank"
            rel="noopener noreferrer"
          >
            <span aria-hidden="true">▶</span> Watch trailer
          </a>
        ) : null}
        <button
          type="button"
          className={cn("movie-action", watchlist && "active")}
          data-action="watchlist"
          aria-pressed={watchlist}
          onClick={() => setWatchlist((current) => !current)}
        >
          {watchlist ? "✓ In watchlist" : "+ Watchlist"}
        </button>
        <button
          type="button"
          className={cn("movie-action", favorite && "active")}
          data-action="favorite"
          aria-pressed={favorite}
          onClick={() => setFavorite((current) => !current)}
        >
          <span aria-hidden="true">{favorite ? "♥" : "♡"}</span> Favorite
        </button>
        {tmdb ? (
          <a
            className="movie-action"
            href={tmdb}
            target="_blank"
            rel="noopener noreferrer"
          >
            TMDB <span aria-hidden="true">↗</span>
          </a>
        ) : null}
      </div>
    </>
  );
}

function FactsTile({ detail }: { detail: MovieDetail }) {
  return (
    <div className="movie-tile t-facts">
      <div className="movie-panel-head">
        <h2>Details</h2>
        <span className="movie-panel-sub">TMDB</span>
      </div>
      <dl className="movie-facts">
        {(detail.facts || []).map((fact, index) => (
          <div key={index}>
            <dt>{fact.label}</dt>
            <dd className={cn(fact.muted && "muted")}>{fact.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function StoryTile({ detail }: { detail: MovieDetail }) {
  return (
    <section className="movie-tile t-story" aria-labelledby="movieStoryHeading">
      <div className="movie-panel-head">
        <h2 id="movieStoryHeading">Synopsis</h2>
        <span className="movie-panel-sub">TMDB overview</span>
      </div>
      <p className="movie-lede">
        {detail.overview || "TMDB has no overview for this film yet."}
      </p>
      {(detail.keywords || []).length ? (
        <ul className="movie-keywords" aria-label="TMDB keywords">
          {(detail.keywords || []).map((word) => (
            <li key={word}>{word}</li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

function CastTile({ detail, art }: { detail: MovieDetail; art: Record<string, string> }) {
  const cast = detail.cast || [];
  if (!cast.length) return null;
  return (
    <section className="movie-tile t-cast" aria-labelledby="movieCastHeading">
      <div className="movie-panel-head">
        <h2 id="movieCastHeading">Cast &amp; crew</h2>
        <span className="movie-panel-sub">{cast.length} from TMDB</span>
      </div>
      <div className="movie-cast-grid">
        {cast.map((credit, index) => (
          <figure key={index} className="movie-cast-card" data-slot={`cast:${index}`}>
            <div className="movie-cast-crop">
              <SlotArt
                uri={art[`cast:${index}`] || ""}
                alt={credit.name}
                imageClass="movie-cast-img"
                placeholder={initialsOf(credit.name)}
                placeholderClass="movie-cast-placeholder"
              />
            </div>
            <figcaption className="movie-cast-meta">
              <span className="name">{credit.name}</span>
              <span className={cn("role", credit.crew && "crew")}>
                {credit.role || (credit.crew ? "Crew" : "Cast")}
              </span>
            </figcaption>
          </figure>
        ))}
      </div>
    </section>
  );
}

function GalleryTile({ detail, art }: { detail: MovieDetail; art: Record<string, string> }) {
  const stills = detail.stills || [];
  if (!stills.length) return null;
  return (
    <section className="movie-tile t-gallery" aria-labelledby="movieArtHeading">
      <div className="movie-panel-head">
        <h2 id="movieArtHeading">Artwork</h2>
        <span className="movie-panel-sub">{stills.length} stills</span>
      </div>
      <div className="movie-gallery">
        {stills.map((still, index) => (
          <figure key={index} className="movie-shot" data-slot={`still:${index}`}>
            <SlotArt
              uri={art[`still:${index}`] || ""}
              alt={still.caption || "Film still " + (index + 1)}
              imageClass="movie-shot-img"
              placeholder={FILM_ICON}
              placeholderClass="movie-shot-placeholder"
            />
            {still.caption ? <figcaption>{still.caption}</figcaption> : null}
          </figure>
        ))}
      </div>
    </section>
  );
}

function SimilarCard({
  entry,
  uri,
}: {
  entry: SimilarMovie;
  uri: string;
}) {
  const movie = entry.movie || ({} as Movie);
  const score =
    Number(movie.rating) > 0 ? (
      <span className="movie-sim-score">{Number(movie.rating).toFixed(1)}</span>
    ) : null;

  return (
    <button
      type="button"
      className="movie-sim-card"
      data-action="similar"
      data-id={String(movie.id)}
      data-title={movie.title || ""}
      data-year={String(movie.year || "")}
      onClick={() =>
        openDetail("movie", {
          id: movie.id,
          title: movie.title,
          year: movie.year,
        })
      }
    >
      <span className="movie-sim-poster" data-slot={`similar:${String(movie.id)}`}>
        <SlotArt
          uri={uri}
          alt={`${movie.title || ""} poster`}
          imageClass="movie-sim-img"
          placeholder={FILM_ICON}
          placeholderClass="movie-sim-placeholder"
          placeholderTag="span"
        >
          {score}
        </SlotArt>
      </span>
      <span className="movie-sim-title">{movie.title || "Untitled"}</span>
      <span className="movie-sim-year">{String(movie.year || "–")}</span>
    </button>
  );
}

function SimilarTile({ detail, art }: { detail: MovieDetail; art: Record<string, string> }) {
  const similar = detail.similar || [];
  if (!similar.length) return null;
  return (
    <section className="movie-tile t-sim" aria-labelledby="movieSimHeading">
      <div className="movie-panel-head">
        <h2 id="movieSimHeading">More like this</h2>
        <span className="movie-panel-sub">{similar.length} films</span>
      </div>
      <div className="movie-sim-row">
        {similar.map((entry, index) => (
          <SimilarCard
            key={entry.movie?.id ?? index}
            entry={entry}
            uri={art[`similar:${String(entry.movie?.id)}`] || ""}
          />
        ))}
      </div>
    </section>
  );
}

function ReadyMarkup({
  detail,
  art,
}: {
  detail: MovieDetail;
  art: Record<string, string>;
}) {
  return (
    <>
      <BackRow />
      <div className="movie-bento">
        <div className="movie-tile t-poster" data-slot="poster">
          <div className="movie-poster-frame">
            <SlotArt
              uri={art.poster || ""}
              alt={`${detail.title} poster`}
              imageClass="movie-poster-img"
              placeholder={FILM_ICON}
            />
          </div>
        </div>
        <div className="movie-tile t-ident">
          <Identity detail={detail} />
        </div>
        <FactsTile detail={detail} />
        <StoryTile detail={detail} />
        <CastTile detail={detail} art={art} />
        <GalleryTile detail={detail} art={art} />
        <SimilarTile detail={detail} art={art} />
      </div>
    </>
  );
}

export function MovieDetailView() {
  const detailState = useStore((state) => state.sessions.movie.detail);
  if (!detailState) return null;

  const movie = (detailState.item ?? null) as Movie | null;
  const detail = detailState.detail;

  if (detailState.status === "loading") {
    return <LoadingMarkup movie={movie ?? {}} />;
  }
  if (detailState.status === "error" || !detail) {
    return (
      <ErrorMarkup
        movie={movie ?? {}}
        error={detailState.error || "unknown error"}
      />
    );
  }

  return <ReadyMarkup detail={detail} art={detailState.art} />;
}
