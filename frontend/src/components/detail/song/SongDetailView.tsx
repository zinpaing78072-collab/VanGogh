import { useEffect, useState, type CSSProperties } from "react";
import { useStore } from "../../../app/store";
import { backToBrowse, openDetail } from "../../../app/actions";
import type { Credit, SongDetail, Song, RelatedSong } from "../../../bridge/types";
import { formatCount, formatStat, formatTime, isHexColor, secureLink } from "../../../lib/format";
import { cn } from "../../../lib/cn";
import { youtubeVideoId } from "../../../lib/youtube";
import { YouTubePlayerOverlay } from "../../media/YouTubePlayer";
import { hidePlayer, resetPlayer, seekPlayer, togglePlayer } from "./player";

/**
 * Song detail: the bento layout from prototypes/song-detail-layouts/bento.html.
 *
 * Data arrives from the Genius detail pair; the reply carries the hero artwork
 * as a data URI, so there is no separate art drain. Lyrics, duration and audio
 * are dummy data (the backend marks them so); the player is a UI-only timer.
 */

const DISC = (
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
    <circle cx="12" cy="12" r="10" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

function creditNames(list: string[] | null | undefined): string {
  return (list || []).length ? (list || []).join(", ") : "–";
}

/** The artwork's own palette, as custom properties on the hero. */
function paletteStyle(detail: SongDetail): CSSProperties {
  const style: Record<string, string> = {};
  const vars: Array<[string, string | null | undefined]> = [
    ["--song-primary", detail.artPrimaryColor],
    ["--song-secondary", detail.artSecondaryColor],
    ["--song-text", detail.artTextColor],
  ];
  vars.forEach(([name, value]) => {
    if (isHexColor(value)) style[name] = String(value);
  });
  return style as CSSProperties;
}

function BackRow({ note }: { note: string }) {
  return (
    <div className="song-back-row">
      <button
        type="button"
        className="song-back"
        data-action="back"
        onClick={() => backToBrowse("music")}
      >
        <span aria-hidden="true">←</span> Back
      </button>
      <span className="song-demo-note">{note}</span>
    </div>
  );
}

function SkeletonLines({ count, step }: { count: number; step: number }) {
  return (
    <>
      {Array.from({ length: count }, (_, index) => (
        <div
          key={index}
          className="skeleton-line"
          style={{ width: 88 - index * step + "%" }}
        />
      ))}
    </>
  );
}

function LoadingMarkup({ song }: { song: Pick<Song, "title" | "artist"> }) {
  return (
    <>
      <BackRow note="Demo lyrics, duration and audio" />
      <div className="song-bento">
        <div className="song-tile t-cover">
          <div className="song-artwork loading">
            <div className="poster-placeholder">{DISC}</div>
          </div>
        </div>
        <div className="song-tile t-head">
          <div className="song-kicker">
            <span>Song</span>
          </div>
          <h1 className="song-title">{song.title || "Untitled"}</h1>
          <div className="song-artist">{song.artist || ""}</div>
          <p className="song-loading-note">Loading song details…</p>
        </div>
        <div className="song-tile t-details">
          <div className="song-panel-head">
            <h2>Details</h2>
          </div>
          <div className="lyrics-skeleton">
            <SkeletonLines count={4} step={8} />
          </div>
        </div>
        <section className="song-tile t-lyrics">
          <div className="song-panel-head">
            <h2>Lyrics</h2>
          </div>
          <div className="lyrics-skeleton">
            <SkeletonLines count={8} step={6} />
          </div>
        </section>
        <aside className="song-tile t-related">
          <div className="song-panel-head">
            <h2>Related Songs</h2>
          </div>
          <div className="related-skeleton">
            {Array.from({ length: 5 }, (_, index) => (
              <div key={index} className="related-skeleton-row">
                <div className="skeleton-thumb" />
                <div className="skeleton-line" style={{ width: "60%" }} />
              </div>
            ))}
          </div>
        </aside>
      </div>
    </>
  );
}

function ErrorMarkup({ song, error }: { song: Pick<Song, "title" | "artist">; error: string }) {
  return (
    <>
      <BackRow note="Demo lyrics, duration and audio" />
      <div className="song-error">
        <h1 className="song-title">{song.title || "Untitled"}</h1>
        <p>Could not load this song&apos;s details: {error || "unknown error"}</p>
        <button
          type="button"
          className="song-back"
          data-action="retry"
          onClick={() => openDetail("music", song)}
        >
          Try again
        </button>
      </div>
    </>
  );
}

function Kicker({ detail }: { detail: SongDetail }) {
  return (
    <div className="song-kicker">
      <span>Song</span>
      {detail.album ? <span className="chip">{detail.album}</span> : null}
      {detail.year ? <span className="chip">{String(detail.year)}</span> : null}
    </div>
  );
}

function PaletteChips({ detail }: { detail: SongDetail }) {
  const colors = [detail.artPrimaryColor, detail.artSecondaryColor, detail.artTextColor].filter(
    (value) => isHexColor(value)
  );
  if (!colors.length) return null;
  return (
    <div className="song-palette" aria-label="Artwork palette">
      {colors.map((color) => (
        <span
          key={color}
          className="palette-chip"
          style={{ background: color }}
          title={color}
        />
      ))}
    </div>
  );
}

function ArtistRow({ detail }: { detail: SongDetail }) {
  const name = detail.artist || "Unknown Artist";
  const href = secureLink(detail.artistUrl);
  return (
    <>
      {detail.artistImageUrl ? (
        <img
          className="artist-avatar"
          src={detail.artistImageUrl}
          alt=""
          loading="lazy"
          decoding="async"
          onError={(event) => event.currentTarget.remove()}
        />
      ) : null}
      {href ? (
        <a className="song-artist" href={href} target="_blank" rel="noopener noreferrer">
          {name}
        </a>
      ) : (
        <span className="song-artist">{name}</span>
      )}
      {detail.artistVerified ? (
        <span className="artist-verified" title="Verified artist on Genius">
          <span aria-hidden="true">✓</span> Verified
        </span>
      ) : null}
    </>
  );
}

function AlbumCell({ detail }: { detail: SongDetail }) {
  const href = secureLink(detail.albumUrl);
  return (
    <div className="song-meta-cell album-cell">
      <span className="k">Album</span>
      <span className="v">
        {detail.albumCoverUrl ? (
          <img
            className="meta-cover"
            src={detail.albumCoverUrl}
            alt=""
            loading="lazy"
            decoding="async"
            onError={(event) => event.currentTarget.remove()}
          />
        ) : null}
        {href ? (
          <a className="meta-link" href={href} target="_blank" rel="noopener noreferrer">
            {detail.album || "–"}
          </a>
        ) : (
          detail.album || "–"
        )}
      </span>
    </div>
  );
}

function mediaType(type: string): string {
  return type === "video" ? "Video" : type === "audio" ? "Audio" : "Link";
}

/**
 * Genius carries a media array of outbound links. The page offers only the
 * YouTube entries — Spotify and SoundCloud links are dropped — and a YouTube
 * entry opens in the shared in-page player at Genius' recorded timestamp,
 * instead of leaving the app.
 */
function ListenLinks({ detail }: { detail: SongDetail }) {
  const [video, setVideo] = useState<{
    videoId: string;
    startSeconds: number;
    title: string;
  } | null>(null);
  const title = detail.artist ? `${detail.title} — ${detail.artist}` : detail.title;
  const links = (detail.media || []).filter(
    (item) =>
      /^youtube$/i.test(String(item.provider || "").trim()) && Boolean(secureLink(item.url))
  );
  if (!links.length) return null;

  return (
    <>
      <div className="song-listen" aria-label="Watch">
        {links.map((item, index) => {
          const href = secureLink(item.url);
          const videoId = youtubeVideoId(item.url);
          const label = (
            <>
              <span className="listen-play" aria-hidden="true">
                ▶
              </span>
              <span className="listen-provider">{item.provider || "YouTube"}</span>
              <span className="listen-type">{mediaType(item.type)}</span>
            </>
          );
          return videoId ? (
            <button
              key={index}
              type="button"
              className="song-listen-link"
              data-action="video"
              onClick={() =>
                setVideo({
                  videoId,
                  startSeconds: Number(item.startSeconds) || 0,
                  title,
                })
              }
            >
              {label}
            </button>
          ) : (
            <a
              key={index}
              className="song-listen-link"
              href={href}
              target="_blank"
              rel="noopener noreferrer"
            >
              {label}
            </a>
          );
        })}
      </div>
      {video ? (
        <YouTubePlayerOverlay
          videoId={video.videoId}
          title={`${video.title} video`}
          startSeconds={video.startSeconds}
          onClose={() => setVideo(null)}
        />
      ) : null}
    </>
  );
}

function LanguagePill({ detail }: { detail: SongDetail }) {
  if (!detail.language) return null;
  const translations = Number(detail.translationCount) || 0;
  return (
    <span className="song-lang" title="Genius' language for this lyric page">
      {detail.language}{" "}
      {translations > 0 ? (
        <span className="lang-count">· {translations} translations</span>
      ) : null}
    </span>
  );
}

function Stats({ detail }: { detail: SongDetail }) {
  const stats = [
    [detail.contributors, "contributors"],
    [detail.annotationCount, "annotations"],
    [detail.pyongsCount, "pyongs"],
    [detail.transcribers, "transcribers"],
  ]
    .map(([value, label]) => [formatStat(value as number), label] as const)
    .filter(([value]) => value !== "");
  if (!stats.length) return null;
  return (
    <div className="song-stats" aria-label="Genius community">
      {stats.map(([value, label]) => (
        <span key={label} className="song-stat">
          <strong>{value}</strong> {label}
        </span>
      ))}
    </div>
  );
}

function About({ detail }: { detail: SongDetail }) {
  const paragraphs = String(detail.about || "")
    .split(/\n{2,}/)
    .map((text) => text.trim())
    .filter(Boolean);
  const needsToggle = paragraphs.length > 2 || (paragraphs[0]?.length ?? 0) > 420;
  const [expanded, setExpanded] = useState(!needsToggle);
  if (!paragraphs.length) return null;

  return (
    <section className="song-tile t-about" aria-labelledby="aboutHeading">
      <div className="song-panel-head">
        <h2 id="aboutHeading">About this song</h2>
        <span className="song-panel-sub">Genius editorial</span>
      </div>
      <div className={cn("about-body", expanded && "expanded")}>
        {paragraphs.map((text, index) => (
          <p key={index}>{text}</p>
        ))}
      </div>
      {needsToggle ? (
        <button
          type="button"
          className="about-toggle"
          data-action="about"
          onClick={() => setExpanded((current) => !current)}
        >
          {expanded ? "Show less" : "Read more"}
        </button>
      ) : null}
    </section>
  );
}

function Credits({ detail }: { detail: SongDetail }) {
  const [open, setOpen] = useState(false);
  const featured = creditNames(detail.featuredArtists);
  const credits: Credit[] = detail.credits || [];

  return (
    <div className="song-tile t-credits" aria-labelledby="creditsHeading">
      <div className="song-panel-head">
        <h2 id="creditsHeading">Credits</h2>
        <span className="song-panel-sub">
          {credits.length ? credits.length + " on Genius" : "Genius"}
        </span>
      </div>
      <div className="song-credits">
        <div className="credit-row">
          <span className="k">WRITTEN BY</span>
          <span className="v">{creditNames(detail.writers)}</span>
        </div>
        <div className="credit-row">
          <span className="k">PRODUCED BY</span>
          <span className="v">{creditNames(detail.producers)}</span>
        </div>
        {featured !== "–" ? (
          <div className="credit-row">
            <span className="k">FEATURING</span>
            <span className="v">{featured}</span>
          </div>
        ) : null}

        {credits.length ? (
          <>
            <button
              type="button"
              className="credits-toggle"
              data-action="credits"
              aria-expanded={open}
              onClick={() => setOpen((current) => !current)}
            >
              All credits <span className="credits-count">{credits.length}</span>
              <span
                className="chev"
                aria-hidden="true"
                style={{ transform: open ? "rotate(180deg)" : undefined }}
              >
                ⌄
              </span>
            </button>
            <div className={cn("credit-all", !open && "hidden")}>
              {credits.map((credit, index) => (
                <div key={index} className="credit-all-row">
                  <span className="k">{credit.label}</span>
                  <span className="v">{creditNames(credit.names)}</span>
                </div>
              ))}
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}

function RelatedArt({ song }: { song: RelatedSong }) {
  const [failedUri, setFailedUri] = useState<string | null>(null);
  const [loadedUri, setLoadedUri] = useState<string | null>(null);
  const failed = failedUri === song.posterUrl;
  const loaded = loadedUri === song.posterUrl;
  const showImage = Boolean(song.posterUrl) && !failed && loaded;
  return (
    <span className="related-art">
      {song.posterUrl && !failed ? (
        <img
          className={cn("related-img", !loaded && "hidden")}
          src={song.posterUrl}
          alt=""
          loading="lazy"
          decoding="async"
          onLoad={() => setLoadedUri(song.posterUrl)}
          onError={() => setFailedUri(song.posterUrl)}
        />
      ) : null}
      <span className={cn("related-placeholder", showImage && "hidden")}>{DISC}</span>
    </span>
  );
}

function Related({ detail }: { detail: SongDetail }) {
  const related = detail.related || [];
  const allHref = secureLink(detail.relationshipsIndexUrl);

  return (
    <aside className="song-tile t-related" aria-labelledby="relatedHeading">
      <div className="song-panel-head">
        <h2 id="relatedHeading">Related Songs</h2>
        <span className="song-panel-sub">{related.length} from Genius</span>
      </div>
      <div className="related-list">
        {related.length ? (
          related.map((song) => (
            <button
              key={song.id}
              type="button"
              className="related-row"
              data-action="related"
              data-id={song.id}
              data-title={song.title || ""}
              data-artist={song.artist || ""}
              onClick={() =>
                openDetail("music", {
                  id: song.id,
                  title: song.title,
                  artist: song.artist,
                })
              }
            >
              <RelatedArt song={song} />
              <span className="related-copy">
                <span className="related-title">{song.title || "Untitled"}</span>
                <span className="related-sub">{song.artist || "Unknown Artist"}</span>
                <span className="related-meta">
                  {song.relation || "Related"} · {formatTime(song.durationSeconds)}
                </span>
              </span>
            </button>
          ))
        ) : (
          <p className="related-empty">No related songs in the Genius set.</p>
        )}
      </div>
      {allHref ? (
        <a className="related-all" href={allHref} target="_blank" rel="noopener noreferrer">
          View all related on Genius <span aria-hidden="true">↗</span>
        </a>
      ) : null}
    </aside>
  );
}

function HeroArtwork({ uri, alt }: { uri: string; alt: string }) {
  const [failedUri, setFailedUri] = useState<string | null>(null);
  const show = Boolean(uri) && failedUri !== uri;
  return (
    <>
      <img
        className={cn("song-art-img", !show && "hidden")}
        alt={alt}
        src={show ? uri : undefined}
        onError={() => setFailedUri(uri)}
      />
      <div className={cn("poster-placeholder", show && "hidden")}>{DISC}</div>
    </>
  );
}

function ReadyMarkup({ detail, artUri }: { detail: SongDetail; artUri: string }) {
  const [favorite, setFavorite] = useState(false);
  const [playlist, setPlaylist] = useState(false);
  const geniusHref = secureLink(detail.geniusUrl);

  const onLyricSeek = (time: string) => {
    const [minutes, seconds] = String(time || "0:00").split(":").map(Number);
    seekPlayer((minutes || 0) * 60 + (seconds || 0));
  };

  return (
    <>
      <BackRow note="Demo lyrics, duration and audio" />
      <div className="song-bento">
        <div className="song-tile t-cover">
          <div className="song-artwork" style={paletteStyle(detail)}>
            <HeroArtwork uri={artUri} alt={`${detail.title} cover`} />
            <div className="song-art-shade" aria-hidden="true" />
            <span className="song-tag">
              SONG{detail.year ? ` · ${detail.year}` : ""}
            </span>
            <button
              type="button"
              className="song-play"
              data-action="play"
              aria-label="Play"
              onClick={togglePlayer}
            >
              ▶
            </button>
          </div>
        </div>

        <div className="song-tile t-head">
          <Kicker detail={detail} />
          <h1 className="song-title">{detail.title}</h1>
          <div className="song-artist-row">
            <ArtistRow detail={detail} />
            {detail.lyricsState === "complete" ? (
              <span className="song-chip">Lyrics on Genius</span>
            ) : null}
          </div>
          <div className="song-actions">
            <button
              type="button"
              className="song-action song-action-primary"
              data-action="play"
              onClick={togglePlayer}
            >
              <span aria-hidden="true">▶</span> Play
            </button>
            <button
              type="button"
              className={cn("song-action", favorite && "active")}
              data-action="favorite"
              onClick={() => setFavorite((current) => !current)}
            >
              <span aria-hidden="true">{favorite ? "♥" : "♡"}</span> Favorite
            </button>
            <button
              type="button"
              className={cn("song-action", playlist && "active")}
              data-action="playlist"
              onClick={() => setPlaylist((current) => !current)}
            >
              <span aria-hidden="true">+</span> Add to playlist
            </button>
            {geniusHref ? (
              <a
                className="song-action"
                href={geniusHref}
                target="_blank"
                rel="noopener noreferrer"
              >
                Genius <span aria-hidden="true">↗</span>
              </a>
            ) : null}
          </div>
          <ListenLinks detail={detail} />
        </div>

        <div className="song-tile t-details" aria-labelledby="detailsHeading">
          <div className="song-panel-head">
            <h2 id="detailsHeading">Details</h2>
            <span className="song-panel-sub">Genius</span>
          </div>
          <div className="song-meta-grid">
            <AlbumCell detail={detail} />
            <div className="song-meta-cell">
              <span className="k">Released</span>
              <span className="v">{detail.releaseDateDisplay || detail.releaseDate || "–"}</span>
            </div>
            <div className="song-meta-cell">
              <span className="k">Pageviews</span>
              <span className="v">{formatCount(detail.pageviews)}</span>
            </div>
            <div className="song-meta-cell">
              <span className="k">Duration</span>
              <span className="v">
                {formatTime(detail.durationSeconds)} <span className="dummy-mark">demo</span>
              </span>
            </div>
            {detail.recordingLocation ? (
              <div className="song-meta-cell wide">
                <span className="k">Recorded at</span>
                <span className="v">{detail.recordingLocation}</span>
              </div>
            ) : null}
          </div>
          <Stats detail={detail} />
          <PaletteChips detail={detail} />
        </div>

        <section className="song-tile t-lyrics" aria-labelledby="lyricsHeading">
          <div className="song-panel-head">
            <h2 id="lyricsHeading">Lyrics</h2>
            <span className="song-panel-meta">
              <LanguagePill detail={detail} />
              <span className="song-panel-sub">Demo copy · placeholder timings</span>
            </span>
          </div>
          <div className="lyrics-list">
            {(detail.lyrics || []).length ? (
              (detail.lyrics || []).map((line, index) => (
                <button
                  key={index}
                  type="button"
                  className="lyrics-line"
                  data-time={line.time || "0:00"}
                  onClick={() => onLyricSeek(line.time || "0:00")}
                >
                  <span className="lyrics-time">{line.time || ""}</span>
                  <span className="lyrics-text">{line.text || ""}</span>
                </button>
              ))
            ) : (
              <p className="lyrics-empty">No lyrics for this song yet.</p>
            )}
          </div>
        </section>

        <About detail={detail} />
        <Credits detail={detail} />
        <Related detail={detail} />
      </div>
    </>
  );
}

export function SongDetailView() {
  const view = useStore((state) => state.view);
  const activeDomain = useStore((state) => state.activeDomain);
  const detailState = useStore((state) => state.sessions.music.detail);
  const artUri = detailState?.art.art || "";

  const active = view === "detail" && activeDomain === "music";
  const status = detailState?.status ?? "idle";
  const detail = detailState?.detail ?? null;
  const song = (detailState?.item ?? null) as Song | null;

  // The player follows the visible detail: ready shows it from zero, anything
  // else (loading, error, another view) hides it.
  useEffect(() => {
    if (!active || status !== "ready" || !detail) {
      hidePlayer();
      return;
    }
    resetPlayer(detail.title, detail.artist, detail.durationSeconds, artUri);
  }, [active, status, detail, artUri]);

  if (!detailState) return null;

  if (status === "loading") {
    return <LoadingMarkup song={song ?? { title: "", artist: "" }} />;
  }
  if (status === "error" || !detail) {
    return (
      <ErrorMarkup
        song={song ?? { title: "", artist: "" }}
        error={detailState.error || "unknown error"}
      />
    );
  }

  return <ReadyMarkup detail={detail} artUri={artUri || detail.artUrl || ""} />;
}
