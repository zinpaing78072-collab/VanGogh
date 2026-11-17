/* Van Gogh content browser (Krema system-webview shell / JCEF experiment shell) */

/* The page talks to Java through window.krema:
   - await window.krema.invoke("requestMovies", { kind, query }) → kicks off the
     IO-pool fetch, returns the request generation immediately (never blocks)
   - await window.krema.invoke("requestGames", { kind, query }) → same for games
   - await window.krema.invoke("requestMusic", { kind, query }) → same for music
   - await window.krema.invoke("requestBooks", { kind, query }) → same for books
   - await window.krema.invoke("pollMovieData", { generation }) → data drain:
     { status: "pending" | "done" | "error" | "superseded", items?, error? }
   - await window.krema.invoke("pollGameData", { generation }) → same for games
   - await window.krema.invoke("pollMusicData", { generation }) → same for music
   - await window.krema.invoke("pollBookData", { generation }) → same for books
   - await window.krema.invoke("pollMoviePosters", {}) → movie poster drain progress
   - await window.krema.invoke("pollGamePosters", {}) → game poster drain progress
   - await window.krema.invoke("pollMusicPosters", {}) → music cover drain progress
   - await window.krema.invoke("pollBookCovers", {}) → book cover drain progress
   - window.krema.on("poster", data) / on("gamePoster", data) / on("musicPoster", data)
     / on("bookCover", data) → artwork data URIs
   The JCEF shell serves this page unchanged: jcef/krema-shim.js implements the
   same window.krema API on top of window.cefQuery.

   Delivery is drained, never pushed, for both data and posters: the drains run
   as synchronous commands on the UI thread (so the reply's engine.eval always
   fires — the Windows Krema constraint) and double as completion signals.

   Structure: DOMAINS is the per-domain contract (IPC names, artwork event and
   shape, card + detail builders). loadContent owns one request's lifecycle
   behind a sequence guard; render() paints state. A new content form is one
   DOMAINS row plus a card builder.
 */

document.addEventListener("DOMContentLoaded", async () => {
    const grid = document.getElementById("movieGrid");
    const searchInput = document.getElementById("searchInput");
    const searchBtn = document.getElementById("searchBtn");
    const contentFilter = document.getElementById("contentFilter");
    const sectionTitle = document.getElementById("sectionTitle");

    // The filter is a custom DOM widget (a div — see the NSMenu-freeze note
    // below), so it has no native .value. The active form lives in
    // state.domain; read that, never contentFilter.value (the select-era read
    // silently disabled the Game filter).
    const state = {
        view: "browse",   // future views dispatch in render(); only "browse" exists
        domain: "movie",
        title: "Trending Movies",
        status: "idle",   // "idle" | "loading" | "ready" | "error"
        items: [],
        error: null,
    };

    // Monotonic request token. A data poll or artwork drain may only touch the
    // page while it still owns the latest token (see loadContent).
    let requestSeq = 0;

    function escapeHtml(text) {
        if (text == null) return "";
        const div = document.createElement("div");
        div.textContent = text;
        return div.innerHTML;
    }

    function messageOf(error) {
        return error && error.message ? error.message : String(error);
    }

    // Posters stream in as data: URIs, so  elements start with no src
    // and the placeholder is visible. If an img carrying a real src fails,
    // fall back to the placeholder.
    function posterFailed(img) {
        img.removeAttribute("src");
        img.classList.add("hidden");
        const placeholder = img.parentElement.querySelector(".poster-placeholder");
        if (placeholder) {
            placeholder.classList.remove("hidden");
        }
    }

    // Shared card shell: CSS class, the data-<domain>-id identity attribute
    // the artwork router targets, and the stagger index for the entrance.
    function baseCard(domainKey, item, idx) {
        const domain = DOMAINS[domainKey];
        const card = document.createElement("div");
        card.className = domain.cardClass;
        card.dataset[domainKey + "Id"] = String(item[domain.idField] || "");
        card.style.setProperty("--i", idx);
        return card;
    }

    // Every card fails back to its placeholder and opens the same way; the
    // domain descriptor supplies the detail content.
    function wireCard(card, onOpen) {
        card.querySelector(".poster-img").addEventListener("error", function () {
            posterFailed(this);
        });
        card.addEventListener("click", onOpen);
    }

    // Movie and game cards share the portrait-poster shape: title, then
    // year / genre / rating.
    function posterCard(domainKey, item, idx) {
        const card = baseCard(domainKey, item, idx);

        const placeholderSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
            <line x1="3" y1="12" x2="21" y2="12"></line>
        </svg>`;

        const title = item.title || item.game_title || "Untitled";
        const year = item.year || (item.release_date ? item.release_date.slice(0, 4) : "–");
        const rating = item.rating ? item.rating.toFixed(1) : "–";
        const genre = item.genre || "";

        card.innerHTML = `
            <div class="card-poster">
                <img class="poster-img hidden" alt="${escapeHtml(title)} poster"
                     loading="lazy" decoding="async">
                <div class="poster-placeholder">${placeholderSvg}</div>
            </div>
            <div class="card-info">
                <div class="card-title">${escapeHtml(title)}</div>
                <div class="card-meta">
                    <span class="card-meta-item">${escapeHtml(year)}</span>
                    <span class="card-meta-item">${escapeHtml(genre)}</span>
                    <span class="card-meta-item card-rating">★ ${rating}</span>
                </div>
            </div>
        `;

        wireCard(card, () => openDetail(domainKey, item));
        return card;
    }

    // Album cards: square cover art (album art is 1:1), the artist as the
    // subtitle line, and type/tags instead of a rating — MusicBrainz has no
    // numeric ratings.
    function albumCard(album, idx) {
        const card = baseCard("music", album, idx);

        const coverSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <circle cx="12" cy="12" r="3"></circle>
        </svg>`;

        const year = album.year || "–";
        const subtitle = [album.primaryType, album.genre].filter(Boolean).join(" · ");

        card.innerHTML = `
            <div class="card-cover">
                <img class="poster-img hidden" alt="${escapeHtml(album.title)} cover"
                     loading="lazy" decoding="async">
                <div class="poster-placeholder">${coverSvg}</div>
            </div>
            <div class="card-info">
                <div class="card-title">${escapeHtml(album.title || "Untitled")}</div>
                <div class="card-subtitle">${escapeHtml(album.artist || "Unknown Artist")}</div>
                <div class="card-meta">
                    <span class="card-meta-item">${escapeHtml(year)}</span>
                    <span class="card-meta-item">${escapeHtml(subtitle)}</span>
                </div>
            </div>
        `;

        wireCard(card, () => openDetail("music", album));
        return card;
    }

    // Book cards keep the design-system Book Card shape: the same portrait
    // poster card, but the meta row reads "year  Author · N pages" and the
    // rating sits on its own row.
    function bookCard(book, idx) {
        const card = baseCard("book", book, idx);

        const coverSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
        </svg>`;

        const rating = book.rating ? book.rating.toFixed(1) : "";
        const year = book.year || "–";
        const authorPages = [book.author, book.pages ? book.pages + " pages" : ""]
            .filter((part) => part).join(" · ");

        card.innerHTML = `
            <div class="card-poster">
                <img class="poster-img hidden" alt="${escapeHtml(book.title)} cover"
                     loading="lazy" decoding="async">
                <div class="poster-placeholder">${coverSvg}</div>
            </div>
            <div class="card-info">
                <div class="card-title">${escapeHtml(book.title)}</div>
                <div class="card-meta">
                    <span class="card-meta-item">${escapeHtml(year)}</span>
                    ${authorPages ? `<span class="card-meta-item">${escapeHtml(authorPages)}</span>` : ""}
                </div>
                ${rating ? `<div class="card-meta"><span class="card-meta-item card-rating">★ ${rating}</span></div>` : ""}
            </div>
        `;

        wireCard(card, () => openDetail("book", book));
        return card;
    }

    // Detail content per form. The detail-view slice replaces openDetail(); the
    // card builders keep calling it unchanged.
    function posterDetail(item) {
        const title = item.title || item.game_title || "Untitled";
        const year = item.year || (item.release_date ? item.release_date.slice(0, 4) : "–");
        return { title: `Selected: ${title} (${year})`, body: item.overview || "" };
    }

    function albumDetail(album) {
        const year = album.year || "–";
        const subtitle = [album.primaryType, album.genre].filter(Boolean).join(" · ");
        const body = [album.artist, subtitle].filter(Boolean).join(" — ");
        return { title: `Selected: ${album.title || "Untitled"} (${year})`, body: body };
    }

    function bookDetail(book) {
        const lines = [];
        if (book.author) lines.push(`Author: ${book.author}`);
        if (book.year) lines.push(`First published: ${book.year}`);
        if (book.rating) lines.push(`Rating: ${book.rating.toFixed(1)} / 5`);
        if (book.pages) lines.push(`Pages: ${book.pages}`);
        if (book.editionCount) lines.push(`Editions catalogued: ${book.editionCount}`);
        if (book.subjects) lines.push(`Subjects: ${book.subjects}`);
        if (book.firstSentence) lines.push("", `“${book.firstSentence}”`);
        return { title: book.title, body: lines.join("\n") };
    }

    // One row per content form: the whole per-domain contract. The existing
    // artwork naming asymmetry (pollMusicPosters/musicPoster vs
    // pollBookCovers/bookCover) lives here instead of in control flow.
    const DOMAINS = {
        movie: {
            request: "requestMovies", data: "pollMovieData", art: "pollMoviePosters",
            event: "poster",
            plural: "Movies", emptyLabel: "movies",
            cardClass: "movie-card", artClass: "card-poster", idField: "id",
            card: (item, idx) => posterCard("movie", item, idx),
            detail: posterDetail,
        },
        game: {
            request: "requestGames", data: "pollGameData", art: "pollGamePosters",
            event: "gamePoster",
            plural: "Games", emptyLabel: "games",
            cardClass: "movie-card game-card", artClass: "card-poster", idField: "id",
            card: (item, idx) => posterCard("game", item, idx),
            detail: posterDetail,
        },
        music: {
            request: "requestMusic", data: "pollMusicData", art: "pollMusicPosters",
            event: "musicPoster",
            plural: "Music", emptyLabel: "albums",
            cardClass: "movie-card music-card", artClass: "card-cover", idField: "id",
            card: albumCard,
            detail: albumDetail,
        },
        book: {
            request: "requestBooks", data: "pollBookData", art: "pollBookCovers",
            event: "bookCover",
            plural: "Books", emptyLabel: "books",
            cardClass: "movie-card book-card", artClass: "card-poster", idField: "workKey",
            card: bookCard,
            detail: bookDetail,
        },
    };

    // Artwork arrives one event per domain. setArtwork finds the card through
    // the same data-<domain>-id identity baseCard set; CSS.escape covers ids
    // with slashes or quotes (Open Library work keys, MusicBrainz MBIDs).
    Object.keys(DOMAINS).forEach((domainKey) => {
        window.krema.on(DOMAINS[domainKey].event, function (data) {
            if (data && data.payload) {
                setArtwork(domainKey, data.payload.id, data.payload.dataUri);
            }
        });
    });

    function setArtwork(domainKey, id, dataUri) {
        if (!dataUri) return;
        const card = grid.querySelector(`[data-${domainKey}-id="${CSS.escape(String(id))}"]`);
        if (!card) return;
        const img = card.querySelector(".poster-img");
        const placeholder = card.querySelector(".poster-placeholder");
        if (!img) return;
        img.src = dataUri;
        img.classList.remove("hidden");
        if (placeholder) {
            placeholder.classList.add("hidden");
        }
    }

    // ---- Rendering ---------------------------------------------------------
    // State changes go through render(); artwork updates only touch a card's
    // image and never rebuild the grid.

    function render() {
        if (state.view === "browse") {
            renderBrowse();
        }
    }

    function renderBrowse() {
        sectionTitle.textContent = state.title;
        if (state.status === "loading") {
            renderSkeletonGrid();
        } else if (state.status === "error") {
            renderEmptyGrid(state.error || "Failed to load content.");
        } else if (state.status === "ready") {
            renderCards(state.domain, state.items);
        }
    }

    function renderCards(domainKey, items) {
        const domain = DOMAINS[domainKey];
        grid.innerHTML = "";
        if (!items || items.length === 0) {
            grid.innerHTML = emptyStateMarkup(`No ${domain.emptyLabel} found.`);
            return;
        }
        items.forEach((item, idx) => grid.appendChild(domain.card(item, idx)));
    }

    function renderEmptyGrid(message) {
        grid.innerHTML = emptyStateMarkup(message);
    }

    function emptyStateMarkup(message) {
        return `
            <div class="empty-state">
                <p style="color:#ff6b6b; margin:0;">${escapeHtml(message)}</p>
            </div>`;
    }

    // Skeleton cards shown the moment a request starts — the grid should
    // never sit empty or stale while the fetch is in flight. The skeleton
    // artwork area matches the active domain's card shape.
    function renderSkeletonGrid() {
        const artClass = DOMAINS[state.domain].artClass;
        grid.innerHTML = "";
        for (let i = 0; i < 8; i++) {
            const card = document.createElement("div");
            card.className = "movie-card skeleton-card";
            card.style.setProperty("--i", i);   // stagger the entrance
            card.innerHTML = `
                <div class="${artClass} skeleton-poster"></div>
                <div class="card-info">
                    <div class="skeleton-line skeleton-title"></div>
                    <div class="skeleton-line skeleton-meta"></div>
                </div>
            `;
            grid.appendChild(card);
        }
    }

    function openDetail(domainKey, item) {
        const detail = DOMAINS[domainKey].detail(item);
        alert(`${detail.title}\n\n${detail.body || ""}`);
    }

    // ---- Requests ----------------------------------------------------------
    // loadContent owns one request end to end. Every continuation checks its
    // sequence token, so a reply or poster drain from a superseded
    // domain/query can never render into the current page.

    async function loadContent(domainKey, kind, term) {
        const domain = DOMAINS[domainKey];
        const seq = ++requestSeq;

        state.domain = domainKey;
        state.title = kind === "search" ? "Search Results" : `Trending ${domain.plural}`;
        state.status = "loading";
        state.items = [];
        state.error = null;
        render();

        if (!window.krema) {
            state.status = "error";
            state.error = "API bridge not ready yet. Please wait a moment and try again.";
            render();
            return;
        }

        const isCurrent = () => seq === requestSeq;

        try {
            // Kicks off the IO-pool fetch and resolves immediately with the
            // request's generation — the UI thread never blocks on network.
            const generation = await window.krema.invoke(domain.request, { kind: kind, query: term });
            if (!isCurrent()) return;   // superseded while the kickoff resolved

            const reply = await pollDataUntilDone(domain.data, generation, isCurrent);
            if (!reply || !isCurrent()) return;   // superseded, stale, or stopped

            if (reply.status === "error") {
                state.status = "error";
                state.error = reply.error || "Failed to load content.";
                render();
                return;
            }

            state.items = reply.items || [];
            state.status = "ready";
            render();
            pollPostersUntilDone(domain.art, isCurrent);
        } catch (e) {
            if (!isCurrent()) return;
            state.status = "error";
            state.error = "Failed to load content: " + messageOf(e);
            render();
        }
    }

    // Poll the data drain until this generation resolves. Returns the done or
    // error reply, or null when the caller no longer owns the page (a newer
    // request superseded it) or the bridge disappeared.
    async function pollDataUntilDone(command, generation, isCurrent) {
        while (window.krema && isCurrent()) {
            let reply;
            try {
                reply = await window.krema.invoke(command, { generation: generation });
            } catch (e) {
                return { status: "error", error: "Failed to load content: " + messageOf(e) };
            }
            if (reply && reply.status === "done") {
                return reply;
            }
            if (reply && reply.status === "error") {
                return reply;
            }
            if (reply && reply.status === "superseded") {
                return null;
            }
            await new Promise((resolve) => setTimeout(resolve, 100));   // "pending"
        }
        return null;
    }

    // The artwork drain doubles as the completion signal: it resolves -1 when
    // nothing is pending. A superseded request stops polling.
    async function pollPostersUntilDone(command, isCurrent) {
        while (window.krema && isCurrent()) {
            let pending;
            try {
                pending = await window.krema.invoke(command, {});
            } catch (e) {
                return;
            }
            if (pending == null || pending < 0) {
                return;
            }
            await new Promise((resolve) => setTimeout(resolve, 150));
        }
    }

    // ---- Search and filter -------------------------------------------------
    function submitSearch() {
        const term = searchInput.value.trim();
        loadContent(state.domain, term === "" ? "trending" : "search", term);
    }

    searchBtn.addEventListener("click", submitSearch);
    // keydown, not keypress: keypress is deprecated and synthetic Enter
    // presses (and some webview IME paths) never fire it.
    searchInput.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
            submitSearch();
        }
    });

    // Custom dropdown. A native <select> opens a modal macOS NSMenu whose
    // event loop fights the CEF/AWT message pump on the AppKit thread and
    // freezes the whole window (menu never closes, red button dead) — so the
    // filter is a plain DOM widget instead.
    function handleFilterChange(value) {
        if (!DOMAINS[value]) return;
        loadContent(value, "trending", "");
    }

    const filterMenu = document.getElementById("contentFilterMenu");
    const filterLabel = document.getElementById("contentFilterLabel");

    function closeFilterMenu() {
        filterMenu.classList.add("hidden");
    }

    contentFilter.addEventListener("click", (e) => {
        const option = e.target.closest(".filter-option");
        if (option) {
            filterMenu.querySelectorAll(".filter-option").forEach((el) =>
                el.classList.toggle("selected", el === option));
            filterLabel.textContent = option.textContent;
            closeFilterMenu();
            handleFilterChange(option.dataset.value);
        } else {
            filterMenu.classList.toggle("hidden");
        }
    });
    document.addEventListener("click", (e) => {
        if (!contentFilter.contains(e.target)) {
            closeFilterMenu();
        }
    });
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
            closeFilterMenu();
        }
    });

    // Seed the initial trending render.
    await loadContent("movie", "trending", "");
});
