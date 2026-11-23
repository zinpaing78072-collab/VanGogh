import type { Song } from "../../bridge/types";
import { CardArtwork, CardShell } from "./CardShell";
import { CoverPlaceholder } from "./Placeholder";

interface MusicCardProps {
  item: Song;
  index: number;
  artwork?: string;
  onOpen: () => void;
}

function albumSubtitle(song: Song): string {
  return [song.primaryType, song.genre].filter(Boolean).join(" · ");
}

/** Square album cover, artist as the subtitle, type · genre as the meta. */
export function MusicCard({ item, index, artwork, onOpen }: MusicCardProps) {
  const year = item.year || "–";
  const subtitle = albumSubtitle(item);

  return (
    <CardShell
      className="music-card"
      index={index}
      identity={{ "data-music-id": String(item.id) }}
      onOpen={onOpen}
    >
      <CardArtwork
        className="card-cover"
        alt={`${item.title || "Untitled"} cover`}
        dataUri={artwork}
        placeholder={<CoverPlaceholder />}
      />
      <div className="card-info">
        <div className="card-title">{item.title || "Untitled"}</div>
        <div className="card-subtitle">{item.artist || "Unknown Artist"}</div>
        <div className="card-meta">
          <span className="card-meta-item">{year}</span>
          <span className="card-meta-item">{subtitle}</span>
        </div>
      </div>
    </CardShell>
  );
}
