import { useState, type CSSProperties, type ReactNode } from "react";
import { cn } from "../../lib/cn";

interface CardShellProps {
  className: string;
  index: number;
  identity: Record<string, string>;
  onOpen: () => void;
  children: ReactNode;
}

/**
 * The shared card shell: entrance stagger (--i), the identity attribute the
 * artwork router targets, and the click policy. The artwork image starts
 * hidden; a failed image falls back to its placeholder instead of a broken
 * icon.
 */
export function CardShell({
  className,
  index,
  identity,
  onOpen,
  children,
}: CardShellProps) {
  return (
    <div
      className={cn("movie-card", className)}
      style={{ "--i": index } as CSSProperties}
      onClick={onOpen}
      {...identity}
    >
      {children}
    </div>
  );
}

interface ArtworkProps {
  className: string;
  alt: string;
  dataUri?: string;
  placeholder: ReactNode;
}

export function CardArtwork({ className, alt, dataUri, placeholder }: ArtworkProps) {
  // The failed URI is the state, not a boolean: a new data URI is a fresh
  // attempt and shows again, like the vanilla poster event re-showing the img.
  const [failedUri, setFailedUri] = useState<string | null>(null);
  const showImage = Boolean(dataUri) && failedUri !== dataUri;

  return (
    <div className={className}>
      <img
        className={cn("poster-img", !showImage && "hidden")}
        alt={alt}
        src={showImage ? dataUri : undefined}
        loading="lazy"
        decoding="async"
        onError={() => setFailedUri(dataUri ?? null)}
      />
      <div className={cn("poster-placeholder", showImage && "hidden")}>{placeholder}</div>
    </div>
  );
}
