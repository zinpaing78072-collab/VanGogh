/**
 * The YouTube video id inside a watch, share, embed, live or shorts link —
 * "" when the link is not YouTube. The movie detail turns the TMDB trailer URL
 * into an in-page embed with this id, so the trailer plays inside the app
 * instead of opening a browser tab.
 *
 * Only an id that looks like one survives (`/^[\w-]{6,}$/`); anything else is
 * treated as absent and the page falls back to the plain watch link.
 */
export function youtubeVideoId(href: string | null | undefined): string {
  const raw = String(href || "").trim();
  if (!raw) return "";

  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return "";
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") return "";

  const host = url.hostname.toLowerCase().replace(/^www\./, "");
  let id = "";

  if (host === "youtu.be") {
    id = url.pathname.split("/")[1] ?? "";
  } else if (
    host === "youtube.com" ||
    host === "m.youtube.com" ||
    host === "music.youtube.com" ||
    host === "youtube-nocookie.com"
  ) {
    if (url.pathname === "/watch") {
      id = url.searchParams.get("v") || "";
    } else {
      const match = url.pathname.match(/^\/(?:embed|live|shorts|v)\/([^/]+)/);
      id = match ? (match[1] ?? "") : "";
    }
  }

  return /^[\w-]{6,}$/.test(id) ? id : "";
}

/** The in-page player URL: autoplay after the button click, no related-video grid. */
export function youtubeEmbedUrl(videoId: string, startSeconds = 0): string {
  const start = Math.max(0, Math.round(Number(startSeconds) || 0));
  return (
    "https://www.youtube.com/embed/" +
    encodeURIComponent(videoId) +
    "?autoplay=1&rel=0&playsinline=1" +
    (start > 0 ? "&start=" + start : "")
  );
}
