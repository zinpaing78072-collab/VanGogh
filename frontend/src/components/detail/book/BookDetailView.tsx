import { useState, type ReactNode } from "react";
import { backToBrowse, openDetail } from "../../../app/actions";
import { useStore } from "../../../app/store";
import type { Book, BookAuthor, BookDetail } from "../../../bridge/types";
import { cn } from "../../../lib/cn";
import { secureLink } from "../../../lib/format";

/**
 * Book detail: the bento layout from prototypes/book-detail-layouts/bento.html.
 *
 * Artwork streams by slot — cover, author, edition:<i>, similar:<workKey> —
 * and the author slot paints twice (identity byline and author tile). A
 * coverless edition keeps its placeholder instead of a broken icon.
 */

const BOOK_ICON = (
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
    <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
    <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
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
    <div className="book-back-row">
      <button
        type="button"
        className="book-back"
        data-action="back"
        onClick={() => backToBrowse("book")}
      >
        <span aria-hidden="true">←</span> Back
      </button>
      <span className="book-source-note">Data and artwork from Open Library</span>
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

function LoadingMarkup({ book }: { book: Partial<Book> }) {
  return (
    <>
      <BackRow />
      <div className="book-bento">
        <div className="book-tile t-cover">
          <div className="book-cover-frame">
            <div className="poster-placeholder">{BOOK_ICON}</div>
          </div>
        </div>
        <div className="book-tile t-ident">
          <div className="book-kicker">
            <span>Novel</span>
            {book.year ? <span className="chip">{String(book.year)}</span> : null}
          </div>
          <h1 className="book-title">{book.title || "Untitled"}</h1>
          <p className="book-loading-note">Loading book details…</p>
        </div>
        <div className="book-tile t-facts">
          <div className="book-panel-head">
            <h2>Record</h2>
            <span className="book-panel-sub">Open Library</span>
          </div>
          <div className="book-skeleton">
            <SkeletonLines count={6} step={8} start={78} />
          </div>
        </div>
        <section className="book-tile t-story">
          <div className="book-panel-head">
            <h2>The book</h2>
            <span className="book-panel-sub">Open Library description</span>
          </div>
          <div className="book-skeleton">
            <SkeletonLines count={4} step={9} start={94} />
          </div>
        </section>
      </div>
    </>
  );
}

function ErrorMarkup({ book, error }: { book: Partial<Book>; error: string }) {
  return (
    <>
      <BackRow />
      <div className="book-error">
        <h1 className="book-title">{book.title || "Untitled"}</h1>
        <p>Could not load this book&apos;s details: {error || "unknown error"}</p>
        <button
          type="button"
          className="book-back"
          data-action="retry"
          onClick={() => openDetail("book", book)}
        >
          Try again
        </button>
      </div>
    </>
  );
}

function Byline({ author, uri }: { author: BookAuthor; uri: string }) {
  const name = author.name || "Unknown author";
  return (
    <div className="book-byline" data-slot="author">
      <span className="book-byline-photo">
        <SlotArt
          uri={uri}
          alt={name}
          imageClass="book-author-img"
          placeholder={initialsOf(name)}
          placeholderClass="book-author-placeholder"
          placeholderTag="span"
        />
      </span>
      <span className="book-byline-who">
        <b>{name}</b>
        {author.dates ? <span>{author.dates}</span> : null}
      </span>
    </div>
  );
}

function Identity({
  detail,
  authorUri,
}: {
  detail: BookDetail;
  authorUri: string;
}) {
  const [sampleOpen, setSampleOpen] = useState(false);
  const [watchlist, setWatchlist] = useState(false);
  const [favorite, setFavorite] = useState(false);

  const chips = [
    detail.year ? String(detail.year) : "",
    detail.pages ? detail.pages + " pages" : "",
    detail.editionCount ? detail.editionCount + " editions" : "",
  ].filter(Boolean);
  const openLibrary = secureLink(detail.openLibraryUrl);

  return (
    <>
      <div className="book-kicker">
        <span>Novel</span>
        {chips.map((value) => (
          <span key={value} className="chip">
            {value}
          </span>
        ))}
      </div>
      <h1 className="book-title">{detail.title}</h1>
      <Byline author={detail.author || ({} as BookAuthor)} uri={authorUri} />

      {Number(detail.rating) > 0 ? (
        <div className="book-score-row">
          <span className="book-score">
            <span className="star" aria-hidden="true">
              ★
            </span>
            {Number(detail.rating).toFixed(1)}
            <span className="max">/5</span>
          </span>
          {formatVotes(detail.ratingCount) ? (
            <span className="book-votes">{formatVotes(detail.ratingCount)} ratings · Open Library</span>
          ) : null}
        </div>
      ) : null}

      <div className="book-actions">
        {detail.firstSentence ? (
          <button
            type="button"
            className={cn("book-action book-action-primary", sampleOpen && "active")}
            data-action="sample"
            aria-pressed={sampleOpen}
            onClick={() => setSampleOpen((current) => !current)}
          >
            <span aria-hidden="true">📖</span> {sampleOpen ? "Hide sample" : "Read sample"}
          </button>
        ) : null}
        <button
          type="button"
          className={cn("book-action", watchlist && "active")}
          data-action="watchlist"
          aria-pressed={watchlist}
          onClick={() => setWatchlist((current) => !current)}
        >
          {watchlist ? "✓ Want to read" : "+ Want to read"}
        </button>
        <button
          type="button"
          className={cn("book-action", favorite && "active")}
          data-action="favorite"
          aria-pressed={favorite}
          onClick={() => setFavorite((current) => !current)}
        >
          <span aria-hidden="true">{favorite ? "♥" : "♡"}</span> Favorite
        </button>
        {openLibrary ? (
          <a
            className="book-action"
            href={openLibrary}
            target="_blank"
            rel="noopener noreferrer"
          >
            Open Library <span aria-hidden="true">↗</span>
          </a>
        ) : null}
      </div>

      {detail.firstSentence ? (
        <p className={cn("book-sample", !sampleOpen && "hidden")}>{detail.firstSentence}</p>
      ) : null}
    </>
  );
}

function FactsTile({ detail }: { detail: BookDetail }) {
  const facts = detail.facts || [];
  return (
    <div className="book-tile t-facts">
      <div className="book-panel-head">
        <h2>Record</h2>
        <span className="book-panel-sub">Open Library</span>
      </div>
      <dl className="book-facts">
        {facts.length ? (
          facts.map((fact, index) => (
            <div key={index} className={cn(fact.spanAll && "span-all")}>
              <dt>{fact.label}</dt>
              <dd className={cn(fact.muted && "muted")}>{fact.value}</dd>
            </div>
          ))
        ) : (
          <div className="span-all">
            <dt>Record</dt>
            <dd className="muted">Open Library has no structured fields for this work.</dd>
          </div>
        )}
      </dl>
    </div>
  );
}

function StoryTile({ detail }: { detail: BookDetail }) {
  return (
    <section className="book-tile t-story" aria-labelledby="bookStoryHeading">
      <div className="book-panel-head">
        <h2 id="bookStoryHeading">The book</h2>
        <span className="book-panel-sub">Open Library description</span>
      </div>
      <p className="book-lede">
        {detail.description || "Open Library has no description for this work yet."}
      </p>
      {(detail.subjects || []).length ? (
        <ul className="book-keywords" aria-label="Open Library subjects">
          {(detail.subjects || []).map((subject) => (
            <li key={subject}>{subject}</li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

function SimilarTile({ detail, art }: { detail: BookDetail; art: Record<string, string> }) {
  const similar = detail.similar || [];
  if (!similar.length) return null;
  return (
    <section className="book-tile t-sim" aria-labelledby="bookSimHeading">
      <div className="book-panel-head">
        <h2 id="bookSimHeading">More like this</h2>
        <span className="book-panel-sub">{similar.length} works</span>
      </div>
      <div className="book-sim-row">
        {similar.map((book) => {
          const score =
            Number(book.rating) > 0 ? (
              <span className="book-sim-score">{Number(book.rating).toFixed(1)}</span>
            ) : null;
          const meta = [book.author, book.year ? String(book.year) : ""]
            .filter(Boolean)
            .join(" · ");
          return (
            <button
              key={book.workKey}
              type="button"
              className="book-sim-card"
              data-action="similar"
              data-work-key={String(book.workKey)}
              data-title={book.title || ""}
              data-year={String(book.year || "")}
              onClick={() =>
                openDetail("book", {
                  workKey: book.workKey,
                  title: book.title,
                  year: book.year,
                })
              }
            >
              <span className="book-sim-poster" data-slot={`similar:${String(book.workKey)}`}>
                <SlotArt
                  uri={art[`similar:${String(book.workKey)}`] || ""}
                  alt={`${book.title || ""} cover`}
                  imageClass="book-sim-img"
                  placeholder={BOOK_ICON}
                  placeholderClass="book-sim-placeholder"
                  placeholderTag="span"
                >
                  {score}
                </SlotArt>
              </span>
              <span className="book-sim-title">{book.title || "Untitled"}</span>
              <span className="book-sim-meta">{meta || "–"}</span>
            </button>
          );
        })}
      </div>
    </section>
  );
}

function EditionsTile({ detail, art }: { detail: BookDetail; art: Record<string, string> }) {
  const editions = detail.editions || [];
  if (!editions.length) return null;
  return (
    <section className="book-tile t-editions" aria-labelledby="bookEditionsHeading">
      <div className="book-panel-head">
        <h2 id="bookEditionsHeading">Other editions</h2>
        <span className="book-panel-sub">
          {editions.length}
          {detail.editionCount > editions.length ? " of " + detail.editionCount : ""}
        </span>
      </div>
      <div className="book-editions-row">
        {editions.map((edition, index) => (
          <div key={index} className="book-edition" data-slot={`edition:${index}`}>
            <span className="book-edition-art">
              <SlotArt
                uri={art[`edition:${index}`] || ""}
                alt={`${edition.year || "Edition"} cover`}
                imageClass="book-edition-img"
                placeholder={BOOK_ICON}
                placeholderClass="book-edition-placeholder"
                placeholderTag="span"
              />
            </span>
            <span className="book-edition-meta">
              <b>{edition.year || "Edition"}</b>
              <span>{edition.isbn || edition.pages || ""}</span>
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}

function AuthorTile({ detail, art }: { detail: BookDetail; art: Record<string, string> }) {
  const author = detail.author || ({} as BookAuthor);
  if (!author.name) return null;
  const wikipedia = secureLink(author.wikipediaUrl);
  const openLibrary = secureLink(author.openLibraryUrl);
  const link = wikipedia ? (
    <a className="book-link-quiet" href={wikipedia} target="_blank" rel="noopener noreferrer">
      Wikipedia <span aria-hidden="true">↗</span>
    </a>
  ) : openLibrary ? (
    <a className="book-link-quiet" href={openLibrary} target="_blank" rel="noopener noreferrer">
      Open Library <span aria-hidden="true">↗</span>
    </a>
  ) : null;

  return (
    <section className="book-tile t-author" aria-labelledby="bookAuthorHeading">
      <div className="book-panel-head">
        <h2 id="bookAuthorHeading">The author</h2>
        <span className="book-panel-sub">Open Library</span>
      </div>
      <div className="book-author-card">
        <div className="book-author-photo" data-slot="author">
          <SlotArt
            uri={art.author || ""}
            alt={author.name}
            imageClass="book-author-img"
            placeholder={initialsOf(author.name)}
            placeholderClass="book-author-placeholder"
            placeholderTag="span"
          />
        </div>
        <div className="book-author-who">
          <span className="name">{author.name}</span>
          {author.dates ? <span className="dates">{author.dates}</span> : null}
          {author.bio ? <p>{author.bio}</p> : null}
          {link}
        </div>
      </div>
    </section>
  );
}

function ReadyMarkup({ detail, art }: { detail: BookDetail; art: Record<string, string> }) {
  return (
    <>
      <BackRow />
      <div className="book-bento">
        <div className="book-tile t-cover" data-slot="cover">
          <div className="book-cover-frame">
            <SlotArt
              uri={art.cover || ""}
              alt={`${detail.title} cover`}
              imageClass="book-cover-img"
              placeholder={BOOK_ICON}
            />
          </div>
        </div>
        <div className="book-tile t-ident">
          <Identity detail={detail} authorUri={art.author || ""} />
        </div>
        <FactsTile detail={detail} />
        <StoryTile detail={detail} />
        <SimilarTile detail={detail} art={art} />
        <EditionsTile detail={detail} art={art} />
        <AuthorTile detail={detail} art={art} />
      </div>
    </>
  );
}

export function BookDetailView() {
  const detailState = useStore((state) => state.sessions.book.detail);
  if (!detailState) return null;

  const book = (detailState.item ?? null) as Book | null;
  const detail = detailState.detail;

  if (detailState.status === "loading") {
    return <LoadingMarkup book={book ?? {}} />;
  }
  if (detailState.status === "error" || !detail) {
    return (
      <ErrorMarkup book={book ?? {}} error={detailState.error || "unknown error"} />
    );
  }

  return <ReadyMarkup detail={detail} art={detailState.art} />;
}
