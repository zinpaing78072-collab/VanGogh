import { useEffect, useRef } from "react";
import { useStore } from "../../app/store";
import { toggleAi } from "../../app/actions";
import { loadContent } from "../../hooks/useContent";
import type { DomainKey } from "../../app/types";

interface HeaderProps {
  domain: DomainKey;
}

/**
 * Search + AI toggle.
 *
 * The input is uncontrolled on purpose: the preview harness sets `.value` and
 * clicks Search in the same task, and a controlled field would still be holding
 * the previous render's string. The store's query is mirrored into the field
 * when a load or a session restore changes it.
 */
export function Header({ domain }: HeaderProps) {
  const ai = useStore((state) => state.ai);
  const storedQuery = useStore((state) => state.sessions[domain].browse.query);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (inputRef.current && inputRef.current.value !== storedQuery) {
      inputRef.current.value = storedQuery;
    }
  }, [storedQuery]);

  const submit = () => {
    const term = (inputRef.current?.value ?? "").trim();
    const kind = term === "" ? "trending" : ai ? "recommend" : "search";
    void loadContent(domain, kind, term);
  };

  return (
    <header className="app-header">
      <div className="search-bar">
        <input
          type="text"
          id="searchInput"
          className="search-input"
          placeholder="Search movies, books, music, games…"
          ref={inputRef}
          defaultValue={storedQuery}
          onKeyDown={(event) => {
            if (event.key === "Enter") submit();
          }}
        />
        <button
          type="button"
          id="aiToggle"
          className="ai-toggle"
          aria-pressed={ai}
          aria-label="AI recommendations"
          title={ai ? "AI recommendations on" : "AI recommendations off"}
          onClick={toggleAi}
        >
          AI
        </button>
        <button id="searchBtn" className="search-btn" onClick={submit}>
          Search
        </button>
      </div>

      <div className="quick-actions">
        <button
          type="button"
          className="icon-btn round"
          aria-label="Notifications"
          aria-disabled="true"
          title="Notifications (coming soon)"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
            <path d="M13.7 21a2 2 0 0 1-3.4 0" />
          </svg>
        </button>
        <button
          type="button"
          className="icon-btn round avatar"
          aria-label="Profile"
          aria-disabled="true"
          title="Profile (coming soon)"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="8" r="4" />
            <path d="M4 21v-1a8 8 0 0 1 16 0v1" />
          </svg>
        </button>
      </div>
    </header>
  );
}
