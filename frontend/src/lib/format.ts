export function messageOf(error: unknown): string {
  if (error && typeof error === "object" && "message" in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === "string" && message) return message;
  }
  return String(error);
}

/** "4:12" — whole seconds, minutes uncapped. */
export function formatTime(total: number | string | null | undefined): string {
  const seconds = Math.max(0, Math.round(Number(total) || 0));
  return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
}

/** "2.4M" / "81K" / "224" / "–" — the compact stat form. */
export function formatCount(value: number | null | undefined): string {
  const n = Number(value) || 0;
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000) return Math.round(n / 1_000) + "K";
  return n > 0 ? String(n) : "–";
}

/** "2,364,330" / "" — the full grouped stat form. */
export function formatStat(value: number | null | undefined): string {
  const n = Number(value) || 0;
  return n > 0 ? n.toLocaleString("en-US") : "";
}

/** Only http(s) links are ever written into an href. */
export function secureLink(href: string | null | undefined): string {
  return /^https?:\/\//i.test(String(href || "")) ? String(href) : "";
}

/** Only a real hex colour is written into a style attribute. */
export function isHexColor(value: string | null | undefined): boolean {
  return /^#[0-9a-fA-F]{3,8}$/.test(String(value || ""));
}
