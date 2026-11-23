import { useState, type ReactNode } from "react";
import { backToBrowse, openDetail } from "../../../app/actions";
import { useStore } from "../../../app/store";
import type { Game, GameDetail, RelatedGame } from "../../../bridge/types";
import { cn } from "../../../lib/cn";
import { secureLink } from "../../../lib/format";
import { useEscape } from "../../../hooks/useEscape";

/**
 * Game detail: the immersive layout from
 * prototypes/game-detail/v2-immersive.html.
 *
 * A fanart hero holds the boxart and the identity, a four-cell stat band
 * follows, then a sticky record rail beside the editorial article (pull quote,
 * overview, screenshot gallery with a lightbox, platforms and play) and the
 * related-games rail. Artwork streams by slot: boxart, hero, still:<i>,
 * related:<id>.
 */

const GAME_ICON = (
  <svg
    width="48"
    height="48"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <line x1="6" y1="11" x2="10" y2="11" />
    <line x1="8" y1="9" x2="8" y2="13" />
    <line x1="15" y1="12" x2="15.01" y2="12" />
    <line x1="18" y1="10" x2="18.01" y2="10" />
    <path d="M17.32 5H6.68a4 4 0 0 0-3.98 3.59l-.9 8A4 4 0 0 0 5.78 21h1.2a2 2 0 0 0 1.79-1.1l.87-1.9h4.72l.87 1.9A2 2 0 0 0 17.02 21h1.2a4 4 0 0 0 3.98-4.41l-.9-8A4 4 0 0 0 17.32 5z" />
  </svg>
);

/** "2018-08-01" → { year: "2018", day: "August 1", full: "Aug 1, 2018" }. */
function released(value: string | null | undefined): { year: string; day: string; full: string } {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value || ""));
  if (!match) {
    return { year: "", day: "", full: value ? String(value) : "" };
  }
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return {
    year: match[1] ?? "",
    day: date.toLocaleDateString("en-US", { month: "long", day: "numeric", timeZone: "UTC" }),
    full: date.toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric",
      timeZone: "UTC",
    }),
  };
}

/** The first sentence of the overview, used as the opening pull quote. */
function firstSentence(text: string | null | undefined): string {
  const value = String(text || "").trim();
  const match = /^[\s\S]*?[.!?](?=\s|$)/.exec(value);
  return match ? match[0].trim() : value;
}

/** Keep authored paragraphs, else group sentences into readable ones. */
function paragraphsOf(text: string | null | undefined): string[] {
  const value = String(text || "").trim();
  if (!value) return [];
  const authored = value
    .split(/\r?\n\s*\r?\n/)
    .map((part) => part.trim())
    .filter(Boolean);
  if (authored.length > 1) return authored;

  const sentences = value.match(/[^.!?]+[.!?]+["')\]]*\s*/g) || [value];
  const chunks: string[] = [];
  let current = "";
  sentences.forEach((sentence) => {
    if (current && (current + sentence).length > 430) {
      chunks.push(current.trim());
      current = "";
    }
    current += sentence;
  });
  if (current.trim()) chunks.push(current.trim());
  return chunks;
}

/** "M - Mature 17+" → { big: "M", small: "Mature 17+" }. */
function ratingParts(rating: string | null | undefined): { big: string; small: string } {
  const value = String(rating || "").trim();
  if (!value) return { big: "–", small: "not on the record" };
  const match = /^([A-Z0-9+]{1,3})\s*-\s*(.+)$/.exec(value);
  if (match) return { big: match[1] ?? "", small: match[2] ?? "" };
  return { big: value, small: "as listed" };
}

function playersText(players: number): string {
  const n = Number(players) || 0;
  if (n <= 0) return "";
  return n === 1 ? "1 player" : n + " players";
}

function metaLine(detail: GameDetail): string[] {
  const parts: string[] = [];
  const release = released(detail.releaseDate);
  if (release.year) parts.push("Released " + release.full);
  if (detail.players > 0) parts.push(playersText(detail.players));
  if ((detail.platforms || []).length) parts.push(detail.platforms.join(", "));
  return parts;
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

  const mark =
    placeholderTag === "span" ? (
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
    <div className="game-back-row">
      <button
        type="button"
        className="game-back"
        data-action="back"
        onClick={() => backToBrowse("game")}
      >
        <span aria-hidden="true">←</span> Back
      </button>
      <span className="game-source-note">Data and artwork from TheGamesDB</span>
    </div>
  );
}

function Chip({ value, quiet }: { value: ReactNode; quiet?: boolean }) {
  if (value === "" || value == null) return null;
  return <span className={cn("game-chip", quiet && "quiet")}>{value}</span>;
}

function Hero({
  detail,
  art,
  watchlist,
  favorite,
  onWatchlist,
  onFavorite,
}: {
  detail: GameDetail;
  art: Record<string, string>;
  watchlist: boolean;
  favorite: boolean;
  onWatchlist: () => void;
  onFavorite: () => void;
}) {
  const release = released(detail.releaseDate);
  const rating = ratingParts(detail.rating);
  const meta = metaLine(detail);
  const studio = (
    detail.developers && detail.developers.length ? detail.developers : detail.publishers || []
  ).join(" · ");
  const record = secureLink(detail.recordUrl);
  const youtube = secureLink(detail.youtubeUrl);

  return (
    <section className="game-hero" aria-labelledby="gameTitle">
      <HeroFanart uri={art.hero || ""} />
      <div className="game-hero-cover" data-slot="boxart">
        <SlotArt
          uri={art.boxart || ""}
          alt={`Boxart of ${detail.title}`}
          imageClass="game-cover-img"
          placeholder={GAME_ICON}
          placeholderClass="game-cover-placeholder"
          placeholderTag="span"
        />
      </div>
      <div className="game-hero-copy">
        <div className="game-hero-title">
          <p className="game-kicker">
            <span>Game</span>
            {release.year ? <Chip value={release.year} /> : null}
          </p>
          {studio ? <p className="game-eyebrow">{studio}</p> : null}
          <h1 className="game-title" id="gameTitle">
            {detail.title}
          </h1>
        </div>
        <p className="game-lede">
          {firstSentence(detail.overview) || "The record carries no overview for this game."}
        </p>
        {meta.length ? (
          <div className="game-meta-row">
            {meta.map((part) => (
              <span key={part}>{part}</span>
            ))}
          </div>
        ) : null}
        <div className="game-chips">
          {(detail.genres || []).map((name) => (
            <Chip key={name} value={name} />
          ))}
          {rating.big !== "–" ? <Chip value={detail.rating} quiet /> : null}
        </div>
        <div className="game-actions">
          <button
            type="button"
            className={cn("game-action game-action-primary", watchlist && "active")}
            data-action="watchlist"
            aria-pressed={watchlist}
            onClick={onWatchlist}
          >
            <span aria-hidden="true">{watchlist ? "✓" : "+"}</span>{" "}
            {watchlist ? "In My Lists" : "Add to My Lists"}
          </button>
          <button
            type="button"
            className={cn("game-action", favorite && "active")}
            data-action="favorite"
            aria-pressed={favorite}
            onClick={onFavorite}
          >
            <span aria-hidden="true">{favorite ? "♥" : "♡"}</span> Favorite
          </button>
          {youtube ? (
            <a
              className="game-action"
              href={youtube}
              target="_blank"
              rel="noopener noreferrer"
            >
              Watch trailer <span aria-hidden="true">↗</span>
            </a>
          ) : null}
          {record ? (
            <a
              className="game-action"
              href={record}
              target="_blank"
              rel="noopener noreferrer"
            >
              TheGamesDB <span aria-hidden="true">↗</span>
            </a>
          ) : null}
        </div>
      </div>
    </section>
  );
}

function HeroFanart({ uri }: { uri: string }) {
  const [failedUri, setFailedUri] = useState<string | null>(null);
  const show = Boolean(uri) && failedUri !== uri;
  return (
    <img
      className={cn("game-hero-bg", !show && "hidden")}
      data-slot="hero"
      alt=""
      src={show ? uri : undefined}
      onError={() => setFailedUri(uri)}
    />
  );
}

function Stat({ label, big, small }: { label: string; big: string; small: string }) {
  return (
    <div className="game-stat">
      <dt>{label}</dt>
      <dd>
        {big}
        {small ? <small>{small}</small> : null}
      </dd>
    </div>
  );
}

function StatBand({ detail }: { detail: GameDetail }) {
  const release = released(detail.releaseDate);
  const rating = ratingParts(detail.rating);
  const platforms = (detail.platforms || []).join(", ");
  return (
    <dl className="game-stat-band" aria-label="Key facts">
      <Stat
        label="Released"
        big={release.year || "–"}
        small={release.day || "not on the record"}
      />
      <Stat label="Age rating" big={rating.big} small={rating.small} />
      <Stat
        label="Players"
        big={detail.players > 0 ? String(detail.players) : "–"}
        small={detail.players > 0 ? "from the record" : "not on the record"}
      />
      <Stat
        label="Platform"
        big={platforms || "–"}
        small={platforms ? "as listed on the record" : "not on the record"}
      />
    </dl>
  );
}

function Rail({
  detail,
  onJumpStills,
}: {
  detail: GameDetail;
  onJumpStills: () => void;
}) {
  const facts = detail.facts || [];
  const record = secureLink(detail.recordUrl);
  const stills = (detail.stillUrls || []).length;

  return (
    <aside className="game-rail" aria-label="The record">
      <div className="game-panel">
        <div className="game-panel-head">
          <h2>The record</h2>
          <span className="game-panel-sub">TheGamesDB</span>
        </div>
        <dl className="game-facts">
          {facts.length ? (
            facts.map((fact, index) => (
              <div key={index}>
                <dt>{fact.label}</dt>
                <dd className={cn(/^(Game ID)$/.test(fact.label) && "mono")}>{fact.value}</dd>
              </div>
            ))
          ) : (
            <div>
              <dt>Record</dt>
              <dd className="muted">TheGamesDB has no structured fields for this game.</dd>
            </div>
          )}
        </dl>
      </div>
      <div className="game-rail-actions">
        {record ? (
          <a
            className="game-rail-link"
            href={record}
            target="_blank"
            rel="noopener noreferrer"
          >
            <span aria-hidden="true">↗</span>TheGamesDB record
          </a>
        ) : null}
        {stills ? (
          <a
            className="game-rail-link"
            href="#gameStills"
            data-action="jump-stills"
            onClick={(event) => {
              event.preventDefault();
              onJumpStills();
            }}
          >
            <span aria-hidden="true">□</span>Jump to the stills
          </a>
        ) : null}
      </div>
      <p className="game-rail-note">
        Every field above is the TheGamesDB record. The page adds no price, playtime, store
        link or review score; the age rating is the record&apos;s own string.
      </p>
    </aside>
  );
}

function Overview({ detail }: { detail: GameDetail }) {
  const paragraphs = paragraphsOf(detail.overview);
  const quote = firstSentence(detail.overview);
  return (
    <section className="game-section" aria-labelledby="gameOverviewHeading">
      {quote ? (
        <blockquote className="game-opening">
          <p>&ldquo;{quote}&rdquo;</p>
        </blockquote>
      ) : null}
      <header className="game-section-head">
        <div>
          <p className="game-kicker">The overview</p>
          <h2 className="game-section-title" id="gameOverviewHeading">
            From the record
          </h2>
        </div>
        <span className="game-count">
          {paragraphs.length
            ? paragraphs.length + (paragraphs.length === 1 ? " paragraph" : " paragraphs")
            : "no overview"}
        </span>
      </header>
      <div className="game-prose">
        {paragraphs.length ? (
          paragraphs.map((part, index) => <p key={index}>{part}</p>)
        ) : (
          <p className="muted">This record has no overview text yet.</p>
        )}
      </div>
    </section>
  );
}

function Stills({
  detail,
  art,
  onOpen,
}: {
  detail: GameDetail;
  art: Record<string, string>;
  onOpen: (index: number) => void;
}) {
  const stills = detail.stillUrls || [];
  if (!stills.length) return null;
  return (
    <section className="game-section" id="gameStills" aria-labelledby="gameStillsHeading">
      <header className="game-section-head">
        <div>
          <p className="game-kicker">Field footage</p>
          <h2 className="game-section-title" id="gameStillsHeading">
            Screenshots
          </h2>
        </div>
        <span className="game-count">{stills.length} on the record</span>
      </header>
      <div className={cn("game-stills", stills.length === 1 && "single")}>
        {stills.map((_url, index) => (
          <figure key={index} className="game-still" data-slot={`still:${index}`}>
            <button
              type="button"
              className="game-still-open"
              data-action="still"
              data-index={index}
              aria-label={`Enlarge screenshot ${index + 1} of ${stills.length}`}
              onClick={() => onOpen(index)}
            >
              <SlotArt
                uri={art[`still:${index}`] || ""}
                alt={`${detail.title} screenshot ${index + 1}`}
                imageClass="game-still-img"
                placeholder={GAME_ICON}
                placeholderClass="game-still-placeholder"
                placeholderTag="span"
              />
            </button>
            <figcaption>Screenshot {index + 1} · TheGamesDB</figcaption>
          </figure>
        ))}
      </div>
    </section>
  );
}

function PlatformsAndPlay({ detail }: { detail: GameDetail }) {
  const platforms = detail.platforms || [];
  const spec = [
    ["Players", detail.players > 0 ? playersText(detail.players) : ""],
    ["Release", detail.releaseDate || ""],
    ["Genres", (detail.genres || []).join(" · ")],
    ["Age rating", detail.rating || ""],
  ].filter((pair) => pair[1]);
  if (!platforms.length && !spec.length) return null;

  return (
    <section className="game-section" aria-labelledby="gamePlayHeading">
      <header className="game-section-head">
        <div>
          <p className="game-kicker">Platforms &amp; play</p>
          <h2 className="game-section-title" id="gamePlayHeading">
            Where the record says it runs
          </h2>
        </div>
      </header>
      {platforms.length ? (
        <div className="game-platform-grid">
          {platforms.map((name) => (
            <div key={name} className="game-platform-tile">
              <span className="game-platform-mark" aria-hidden="true">
                ▣
              </span>
              <span>
                <b>{name}</b>
                <span>listed on the record</span>
              </span>
            </div>
          ))}
        </div>
      ) : null}
      {spec.length ? (
        <dl className="game-spec-grid">
          {spec.map(([label, value]) => (
            <div key={label} className="game-spec-row">
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </section>
  );
}

function SourceNote({ detail }: { detail: GameDetail }) {
  return (
    <div className="game-source-card">
      <span className="game-source-mark" aria-hidden="true">
        i
      </span>
      <span>
        <b>Record data.</b> The copy above is TheGamesDB&apos;s overview for game {String(detail.id)},
        shown unedited apart from paragraph breaks. Artwork comes from the same API through the
        image proxy, so a missing image leaves its placeholder instead of a broken icon.
      </span>
    </div>
  );
}

function Related({ detail, art }: { detail: GameDetail; art: Record<string, string> }) {
  const related = detail.related || [];
  if (!related.length) return null;
  const kicker = detail.relatedSource === "series" ? "Same series" : "From the catalogue";

  return (
    <section className="game-related" aria-labelledby="gameRelatedHeading">
      <header className="game-section-head">
        <div>
          <p className="game-kicker">{kicker}</p>
          <h2 className="game-section-title" id="gameRelatedHeading">
            More games
          </h2>
        </div>
        <span className="game-count">{related.length}</span>
      </header>
      <div className="game-related-row">
        {related.map((game: RelatedGame) => {
          const meta = [game.year ? String(game.year) : "", game.rating, game.genre]
            .filter(Boolean)
            .join(" · ");
          return (
            <button
              key={game.id}
              type="button"
              className="game-rel-card"
              data-action="related"
              data-id={String(game.id)}
              data-title={game.title || ""}
              onClick={() =>
                openDetail("game", { id: Number(game.id), title: game.title })
              }
            >
              <span className="game-rel-poster" data-slot={`related:${String(game.id)}`}>
                <SlotArt
                  uri={art[`related:${String(game.id)}`] || ""}
                  alt={`${game.title || ""} boxart`}
                  imageClass="game-rel-img"
                  placeholder={GAME_ICON}
                  placeholderClass="game-rel-placeholder"
                  placeholderTag="span"
                />
              </span>
              <span className="game-rel-title">{game.title || "Untitled"}</span>
              <span className="game-rel-meta">{meta || "–"}</span>
            </button>
          );
        })}
      </div>
    </section>
  );
}

function Lightbox({
  still,
  onClose,
}: {
  still: { src: string; alt: string; caption: string } | null;
  onClose: () => void;
}) {
  const open = still !== null;
  useEscape(onClose, open);

  return (
    <div
      className={cn("game-lightbox", !open && "hidden")}
      role="dialog"
      aria-modal="true"
      aria-label="Screenshot"
      data-lightbox-open={open ? "true" : undefined}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <button
        type="button"
        className="game-lightbox-close"
        data-action="close-lightbox"
        aria-label="Close screenshot"
        onClick={onClose}
      >
        ×
      </button>
      <figure>
        <img className="game-lightbox-img" alt={still?.alt || ""} src={still?.src} />
        <figcaption className="game-lightbox-caption">{still?.caption || ""}</figcaption>
      </figure>
    </div>
  );
}

function ReadyMarkup({ detail, art }: { detail: GameDetail; art: Record<string, string> }) {
  const [watchlist, setWatchlist] = useState(false);
  const [favorite, setFavorite] = useState(false);
  const [lightbox, setLightbox] = useState<{ src: string; alt: string; caption: string } | null>(
    null
  );

  const openStill = (index: number) => {
    const src = art[`still:${index}`] || "";
    if (!src) return;
    setLightbox({
      src,
      alt: `${detail.title} screenshot ${index + 1}`,
      caption: `Screenshot ${index + 1} · TheGamesDB`,
    });
  };

  return (
    <>
      <BackRow />
      <Hero
        detail={detail}
        art={art}
        watchlist={watchlist}
        favorite={favorite}
        onWatchlist={() => setWatchlist((current) => !current)}
        onFavorite={() => setFavorite((current) => !current)}
      />
      <StatBand detail={detail} />
      <div className="game-body">
        <Rail
          detail={detail}
          onJumpStills={() => {
            document
              .getElementById("gameStills")
              ?.scrollIntoView({ behavior: "smooth", block: "start" });
          }}
        />
        <article className="game-article">
          <Overview detail={detail} />
          <Stills detail={detail} art={art} onOpen={openStill} />
          <PlatformsAndPlay detail={detail} />
          <SourceNote detail={detail} />
        </article>
      </div>
      <Related detail={detail} art={art} />
      <Lightbox still={lightbox} onClose={() => setLightbox(null)} />
    </>
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

type GameLike = Partial<Game> & { title?: string };

function gameTitle(game: GameLike): string {
  return game.game_title || game.title || "Untitled";
}

function LoadingMarkup({ game }: { game: GameLike }) {
  const year = released(game.release_date).year;
  return (
    <>
      <BackRow />
      <section className="game-hero loading" aria-busy="true">
        <div className="game-hero-cover">
          <span className="poster-placeholder game-cover-placeholder">{GAME_ICON}</span>
        </div>
        <div className="game-hero-copy">
          <div className="game-hero-title">
            <p className="game-kicker">
              <span>Game</span>
              {year ? <Chip value={year} /> : null}
            </p>
            <h1 className="game-title">{game.game_title || game.title || "Loading"}</h1>
          </div>
          <p className="game-lede">Loading the record…</p>
          <div className="game-loading-lines">
            <div className="skeleton-line" style={{ width: "72%" }} />
            <div className="skeleton-line" style={{ width: "48%" }} />
          </div>
        </div>
      </section>
      <div className="game-body">
        <aside className="game-rail">
          <div className="game-panel">
            <div className="game-panel-head">
              <h2>The record</h2>
              <span className="game-panel-sub">TheGamesDB</span>
            </div>
            <div className="game-skeleton">
              <SkeletonLines count={6} step={7} start={76} />
            </div>
          </div>
        </aside>
        <article className="game-article">
          <section className="game-section">
            <div className="game-skeleton">
              <SkeletonLines count={5} step={9} start={94} />
            </div>
          </section>
        </article>
      </div>
    </>
  );
}

function ErrorMarkup({ game, error }: { game: GameLike; error: string }) {
  return (
    <>
      <BackRow />
      <div className="game-error">
        <h1 className="game-title">{gameTitle(game)}</h1>
        <p>Could not load this game&apos;s details: {error || "unknown error"}</p>
        <button
          type="button"
          className="game-back"
          data-action="retry"
          onClick={() => openDetail("game", game)}
        >
          Try again
        </button>
      </div>
    </>
  );
}

export function GameDetailView() {
  const detailState = useStore((state) => state.sessions.game.detail);
  if (!detailState) return null;

  const game = (detailState.item ?? null) as GameLike | null;
  const detail = detailState.detail;

  if (detailState.status === "loading") {
    return <LoadingMarkup game={game ?? {}} />;
  }
  if (detailState.status === "error" || !detail) {
    return (
      <ErrorMarkup game={game ?? {}} error={detailState.error || "unknown error"} />
    );
  }

  return <ReadyMarkup detail={detail} art={detailState.art} />;
}
