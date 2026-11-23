import type { Book } from "../../bridge/types";
import { CardArtwork, CardShell } from "./CardShell";
import { BookPlaceholder } from "./Placeholder";

interface BookCardProps {
  item: Book;
  index: number;
  artwork?: string;
  onOpen: () => void;
}

/** The design-system Book Card: portrait poster, prose meta, rating row. */
export function BookCard({ item, index, artwork, onOpen }: BookCardProps) {
  const rating = item.rating ? item.rating.toFixed(1) : "";
  const year = item.year || "–";
  const authorPages = [item.author, item.pages ? item.pages + " pages" : ""]
    .filter((part) => part)
    .join(" · ");

  return (
    <CardShell
      className="book-card"
      index={index}
      identity={{ "data-book-id": String(item.workKey) }}
      onOpen={onOpen}
    >
      <CardArtwork
        className="card-poster"
        alt={`${item.title} cover`}
        dataUri={artwork}
        placeholder={<BookPlaceholder />}
      />
      <div className="card-info">
        <div className="card-title">{item.title}</div>
        <div className="card-meta">
          <span className="card-meta-item">{year}</span>
          {authorPages ? <span className="card-meta-item">{authorPages}</span> : null}
        </div>
        {rating ? (
          <div className="card-meta">
            <span className="card-meta-item card-rating">★ {rating}</span>
          </div>
        ) : null}
      </div>
    </CardShell>
  );
}
