import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { useEscape } from "../../hooks/useEscape";
import { youtubeEmbedUrl } from "../../lib/youtube";

/**
 * The in-app YouTube player, shared by the movie trailer and the song's video
 * links. A fixed overlay above the shell (sidebar 110, header 100), portaled
 * onto `document.body` so a detail section's entry animation (a containing
 * block and stacking context) cannot pin or bury it.
 *
 * `data-lightbox-open` tells the app-level Escape handler to leave the detail
 * alone: Escape closes the player, then a second Escape leaves the page (the
 * same convention as the game lightbox). The iframe unmounts on close, which
 * also stops playback.
 */
export function YouTubePlayerOverlay({
  videoId,
  title,
  startSeconds = 0,
  onClose,
}: {
  videoId: string;
  title: string;
  startSeconds?: number;
  onClose: () => void;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);
  useEscape(onClose);

  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  return createPortal(
    <div
      className="youtube-player"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      data-lightbox-open="true"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <button
        ref={closeRef}
        type="button"
        className="youtube-player-close"
        data-action="close-video"
        aria-label="Close video"
        onClick={onClose}
      >
        ×
      </button>
      <div className="youtube-player-frame">
        <iframe
          className="youtube-player-video"
          src={youtubeEmbedUrl(videoId, startSeconds)}
          title={title}
          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share; fullscreen"
          allowFullScreen
          referrerPolicy="strict-origin-when-cross-origin"
        />
      </div>
    </div>,
    document.body
  );
}
