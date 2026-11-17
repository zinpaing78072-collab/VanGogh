/* Van Gogh content browser (Krema system-webview shell / JCEF experiment shell) */

/* The page talks to Java through window.krema:
   - await window.krema.invoke("requestMovies", { kind, query }) → kicks off the
     IO-pool fetch, returns the request generation immediately (never blocks)
   - await window.krema.invoke("requestGames", { kind, query }) → same for games
   - await window.krema.invoke("requestGamesPage", { query, page }) → game search page
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
*/

document.addEventListener("DOMContentLoaded", async () => {
    const grid = document.getElementById("movieGrid");
    const searchInput = document.getElementById("searchInput");
    const searchBtn = document.getElementById("searchBtn");
    const contentFilter = document.getElementById("contentFilter");
    const sectionTitle = document.getElementById("sectionTitle");
    const gamePagination = document.getElementById("gamePagination");
    const gamePreviousBtn = document.getElementById("gamePreviousBtn");
    const gameNextBtn = document.getElementById("gameNextBtn");
    const gamePageLabel = document.getElementById("gamePageLabel");

    // Selected content type ("movie" | "music" | "book" | "game"). The filter
    // is a custom DOM widget (a div — see the NSMenu-freeze note below), so it
    // has no native .value; the old select-based code read contentFilter.value,
    // which became undefined after the widget swap and silently disabled the
    // Game filter (Game selections kept calling requestMovies and rendered
    // movie cards that gamePoster events could never match).
    let selectedContent = "movie";
    let gameSearchTerm = "";
    let gamePreviousPage = null;
    let gameNextPage = null;

    function hideGamePagination() {
        gamePagination.classList.add("hidden");
        gamePreviousBtn.disabled = true;
        gameNextBtn.disabled = true;
        gamePageLabel.textContent = "";
        gamePreviousPage = null;
        gameNextPage = null;
    }

    function updateGamePagination(reply) {
        gamePreviousPage = reply.previousPage == null ? null : reply.previousPage;
        gameNextPage = reply.nextPage == null ? null : reply.nextPage;
        gamePagination.classList.toggle("hidden", gamePreviousPage == null && gameNextPage == null);
        gamePreviousBtn.disabled = gamePreviousPage == null;
        gameNextBtn.disabled = gameNextPage == null;
        gamePageLabel.textContent = `Page ${reply.page || 1}`;
    }

    function escapeHtml(text) {
        if (text == null) return "";
        const div = document.createElement("div");
        div.textContent = text;
        return div.innerHTML;
    }

    // Posters arrive as direct "poster" events pushed by Java (one per
    // prefetched poster); the payload arrives wrapped as
    // { payload, timestamp } by Krema's bridge — and by jcef/krema-shim.js
    // in the JCEF shell.
    window.krema.on("poster", function (data) {
        if (data && data.payload) {
            window.setPoster(data.payload.id, data.payload.dataUri);
        }
    });
    window.krema.on("gamePoster", function (data) {
        if (data && data.payload) {
            window.setGamePoster(data.payload.id, data.payload.dataUri);
        }
    });
    window.krema.on("musicPoster", function (data) {
        if (data && data.payload) {
            window.setMusicPoster(data.payload.id, data.payload.dataUri);
        }
    });
    window.krema.on("bookCover", function (data) {
        if (data && data.payload) {
            window.setBookCover(data.payload.id, data.payload.dataUri);
        }
    });

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

    // Push target called by Java (Krema events) once one poster finishes loading.
    window.setPoster = function (movieId, dataUri) {
        const card = grid.querySelector(`.movie-card[data-movie-id="${movieId}"]`);
        if (!card || !dataUri) return;
        const img = card.querySelector(".poster-img");
        const placeholder = card.querySelector(".poster-placeholder");
        if (!img) return;
        img.src = dataUri;
        img.classList.remove("hidden");
        if (placeholder) {
            placeholder.classList.add("hidden");
        }
    };

    window.setGamePoster = function (gameId, dataUri) {
        const card = grid.querySelector(`.game-card[data-game-id="${gameId}"]`);
        if (!card || !dataUri) return;
        const img = card.querySelector(".poster-img");
        const placeholder = card.querySelector(".poster-placeholder");
        if (!img) return;
        img.src = dataUri;
        img.classList.remove("hidden");
        if (placeholder) {
            placeholder.classList.add("hidden");
        }
    };

    // Music covers target the album card (`.music-card`); the id is the
    // release-group MBID — alphanumeric with dashes, safe in the selector.
    window.setMusicPoster = function (albumId, dataUri) {
        const card = grid.querySelector(`.music-card[data-music-id="${albumId}"]`);
        if (!card || !dataUri) return;
        const img = card.querySelector(".poster-img");
        const placeholder = card.querySelector(".poster-placeholder");
        if (!img) return;
        img.src = dataUri;
        img.classList.remove("hidden");
        if (placeholder) {
            placeholder.classList.add("hidden");
        }
    };

    // Book covers target the book card (`.book-card`, the design-system Book
    // Card shape). The id is the Open Library work key (e.g. "OL27482W") —
    // alphanumeric, so it is safe inside the attribute selector.
    window.setBookCover = function (workKey, dataUri) {
        const card = grid.querySelector(`.book-card[data-book-id="${workKey}"]`);
        if (!card || !dataUri) return;
        const img = card.querySelector(".poster-img");
        const placeholder = card.querySelector(".poster-placeholder");
        if (!img) return;
        img.src = dataUri;
        img.classList.remove("hidden");
        if (placeholder) {
            placeholder.classList.add("hidden");
        }
    };

    // Books render in the design-system Book Card shape: the same vertical
    // poster card as movies/games, but the meta row reads "year  Author · N
    // pages" (design-system.pen: Book Card) and the rating sits on its own
    // row. The detail (click) stays book-specific — subjects, editions, the
    // first line.
    function renderBooks(items) {
        grid.innerHTML = "";
        if (!items || items.length === 0) {
            grid.innerHTML = `
                <div class="empty-state">
                    <p style="color:#ff6b6b; margin:0;">No books found.</p>
                </div>`;
            return;
        }
        items.forEach((book, idx) => {
            const card = document.createElement("div");
            card.className = "movie-card book-card";
            card.dataset.bookId = book.workKey;
            card.style.setProperty("--i", idx);   // stagger the entrance

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

            card.querySelector(".poster-img").addEventListener("error", function () {
                posterFailed(this);
            });

            card.addEventListener("click", () => {
                // Book detail — deliberately its own thing (author, editions,
                // subjects, first line), not the movie/game alert format.
                const lines = [];
                if (book.author) lines.push(`Author: ${book.author}`);
                if (book.year) lines.push(`First published: ${book.year}`);
                if (book.rating) lines.push(`Rating: ${book.rating.toFixed(1)} / 5`);
                if (book.pages) lines.push(`Pages: ${book.pages}`);
                if (book.editionCount) lines.push(`Editions catalogued: ${book.editionCount}`);
                if (book.subjects) lines.push(`Subjects: ${book.subjects}`);
                if (book.firstSentence) lines.push("", `“${book.firstSentence}”`);
                alert(`${book.title}\n\n${lines.join("\n")}`);
            });

            grid.appendChild(card);
        });
    }

    // Album cards use their own shape: square cover art (album art is 1:1),
    // the artist as the subtitle line, and type/tags instead of a rating —
    // MusicBrainz has no numeric ratings.
    function renderMusic(items) {
        grid.innerHTML = "";
        if (!items || items.length === 0) {
            grid.innerHTML = `
                <div class="empty-state">
                    <p style="color:#ff6b6b; margin:0;">No albums found.</p>
                </div>`;
            return;
        }
        items.forEach((album, idx) => {
            const card = document.createElement("div");
            card.className = "movie-card music-card";
            card.dataset.musicId = album.id || "";
            card.style.setProperty("--i", idx);   // stagger the entrance

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

            card.querySelector(".poster-img").addEventListener("error", function () {
                posterFailed(this);
            });

            card.addEventListener("click", () => {
                const detail = [album.artist, subtitle].filter(Boolean).join(" — ");
                alert(`Selected: ${album.title} (${year})\n\n${escapeHtml(detail)}`);
            });

            grid.appendChild(card);
        });
    }

    function renderContent(items, contentType) {
        if (contentType === "book") {
            renderBooks(items);
            return;
        }
        if (contentType === "music") {
            renderMusic(items);
            return;
        }
        grid.innerHTML = "";
        const isGame = contentType === "game";
        const contentName = isGame ? "games" : "movies";
        if (!items || items.length === 0) {
            grid.innerHTML = `
                <div class="empty-state">
                    <p style="color:#ff6b6b; margin:0;">No ${contentName} found.</p>
                </div>`;
            return;
        }
        items.forEach((item, idx) => {
            const title = item.title || item.game_title || "Untitled";
            const year = item.year || (item.release_date ? item.release_date.slice(0, 4) : "–");
            const card = document.createElement("div");
            card.className = isGame ? "movie-card game-card" : "movie-card";
            card.dataset[isGame ? "gameId" : "movieId"] = item.id;
            card.style.setProperty("--i", idx);   // stagger the entrance

            const placeholderSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                <line x1="3" y1="12" x2="21" y2="12"></line>
            </svg>`;

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

            card.querySelector(".poster-img").addEventListener("error", function () {
                posterFailed(this);
            });

            card.addEventListener("click", () => {
                alert(`Selected: ${title} (${year})\n\n${escapeHtml(item.overview || "")}`);
            });

            grid.appendChild(card);
        });
    }

    function showError(message) {
        grid.innerHTML = `
            <div class="empty-state">
                <p style="color:#ff6b6b; margin:0;">${escapeHtml(message)}</p>
            </div>`;
    }

    // Skeleton cards shown the moment a request starts — the grid should
    // never sit empty or stale while the fetch is in flight. The skeleton
    // artwork area matches the domain's card shape (square covers for music,
    // portrait posters otherwise).
    function showLoading() {
        grid.innerHTML = "";
        const posterClass = selectedContent === "music" ? "card-cover" : "card-poster";
        for (let i = 0; i < 8; i++) {
            const card = document.createElement("div");
            card.className = "movie-card skeleton-card";
            card.style.setProperty("--i", i);   // stagger the entrance
            card.innerHTML = `
                <div class="${posterClass} skeleton-poster"></div>
                <div class="card-info">
                    <div class="skeleton-line skeleton-title"></div>
                    <div class="skeleton-line skeleton-meta"></div>
                </div>
            `;
            grid.appendChild(card);
        }
    }

    // Per-domain IPC commands and display names: the request kickoff, the
    // data drain, the artwork drain, and the trending heading. Wiring a new
    // content form is a row here plus a render branch in renderContent.
    const DOMAINS = {
        movie: { request: "requestMovies", data: "pollMovieData", art: "pollMoviePosters", plural: "Movies" },
        game:  { request: "requestGames",  data: "pollGameData",  art: "pollGamePosters",  plural: "Games" },
        music: { request: "requestMusic",  data: "pollMusicData", art: "pollMusicPosters", plural: "Music" },
        book:  { request: "requestBooks",  data: "pollBookData",  art: "pollBookCovers",   plural: "Books" },
    };

    async function requestPageContent(kind, term, requestedPage) {
        if (!window.krema) {
            showError("API bridge not ready yet. Please wait a moment and try again.");
            return;
        }
        const isGameSearch = selectedContent === "game" && kind === "search";
        const page = requestedPage || 1;
        if (isGameSearch) {
            if (page === 1) {
                gameSearchTerm = term;
            } else {
                term = gameSearchTerm;
            }
        }
        hideGamePagination();
        try {
            const domain = DOMAINS[selectedContent];
            sectionTitle.textContent = kind === "search"
                ? "Search Results"
                : `Trending ${domain.plural}`;
            showLoading();
            // Kicks off the IO-pool fetch and resolves immediately with the
            // request's generation — the UI thread never blocks on network.
            const generation = isGameSearch && page > 1
                ? await window.krema.invoke("requestGamesPage", { query: term, page: page })
                : await window.krema.invoke(domain.request, { kind: kind, query: term });
            const reply = await pollDataUntilDone(domain.data, generation);
            if (!reply) return;   // superseded by a newer request, or already shown
            renderContent(reply.items, selectedContent);
            if (isGameSearch) {
                updateGamePagination(reply);
            } else {
                hideGamePagination();
            }
            pollPostersUntilDone(domain.art);
        } catch (e) {
            const message = e && e.message ? e.message : String(e);
            showError("Failed to load content: " + escapeHtml(message));
            if (isGameSearch) {
                hideGamePagination();
            }
        }
    }

    // Poll the data drain until this generation's fetch resolves. Returns the
    // done reply, or null when the loop should stop (error already shown,
    // request superseded by a newer one, or bridge gone).
    async function pollDataUntilDone(command, generation) {
        while (window.krema) {
            let reply;
            try {
                reply = await window.krema.invoke(command, { generation: generation });
            } catch (e) {
                const message = e && e.message ? e.message : String(e);
                showError("Failed to load content: " + escapeHtml(message));
                return null;
            }
            if (reply && reply.status === "done") {
                return reply;
            }
            if (reply && reply.status === "error") {
                showError(reply.error || "Failed to load content.");
                return null;
            }
            if (reply && reply.status === "superseded") {
                return null;
            }
            await new Promise((resolve) => setTimeout(resolve, 100));   // "pending"
        }
        return null;
    }

    // Seed the initial trending render.
    await requestPageContent("trending", "");

    gamePreviousBtn.addEventListener("click", () => {
        if (gamePreviousPage != null) {
            requestPageContent("search", gameSearchTerm, gamePreviousPage);
        }
    });
    gameNextBtn.addEventListener("click", () => {
        if (gameNextPage != null) {
            requestPageContent("search", gameSearchTerm, gameNextPage);
        }
    });

    searchBtn.addEventListener("click", () => {
        const term = searchInput.value.trim();
        if (term === "") {
            requestPageContent("trending", "");
        } else {
            requestPageContent("search", term);
        }
    });
    searchInput.addEventListener("keypress", (e) => {
        if (e.key === "Enter") {
            const term = searchInput.value.trim();
            if (term === "") {
                requestPageContent("trending", "");
            } else {
                requestPageContent("search", term);
            }
        }
    });

    // Custom dropdown. A native <select> opens a modal macOS NSMenu whose
    // event loop fights the CEF/AWT message pump on the AppKit thread and
    // freezes the whole window (menu never closes, red button dead) — so the
    // filter is a plain DOM widget instead.
    function handleFilterChange(value) {
        selectedContent = value;
        if (DOMAINS[value]) {
            requestPageContent("trending", "");
        } else {
            const label = value.charAt(0).toUpperCase() + value.slice(1);
            sectionTitle.textContent = `${label} (not connected)`;
            grid.innerHTML = `
                <div class="empty-state">
                    <p>${escapeHtml(label)} results are not yet connected to the API.</p>
                </div>`;
        }
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

    async function pollPostersUntilDone(command) {
        while (window.krema) {
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
});
