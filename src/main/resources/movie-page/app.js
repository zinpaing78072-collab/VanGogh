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
   - await window.krema.invoke("pollBookPosters", {}) → book cover drain progress
   - window.krema.on("poster", data) / on("gamePoster", data) / on("musicPoster", data)
     / on("bookPoster", data) → poster data URIs
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

    // Selected content type ("movie" | "music" | "book" | "game"). The filter
    // is a custom DOM widget (a div — see the NSMenu-freeze note below), so it
    // has no native .value; the old select-based code read contentFilter.value,
    // which became undefined after the widget swap and silently disabled the
    // Game filter (Game selections kept calling requestMovies and rendered
    // movie cards that gamePoster events could never match).
    let selectedContent = "movie";

    // Per-domain IPC commands. Each domain has a non-blocking kickoff command
    // (returns the generation), a data drain, and a poster drain — same
    // contract everywhere, only the card rendering differs per domain.
    const DOMAINS = {
        movie: { request: "requestMovies", data: "pollMovieData", posters: "pollMoviePosters", trendingTitle: "Trending Movies" },
        music: { request: "requestMusic",  data: "pollMusicData",  posters: "pollMusicPosters",  trendingTitle: "Trending Music" },
        book:  { request: "requestBooks",  data: "pollBookData",   posters: "pollBookPosters",   trendingTitle: "Trending Books" },
        game:  { request: "requestGames",  data: "pollGameData",   posters: "pollGamePosters",   trendingTitle: "Trending Games" },
    };

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
    window.krema.on("bookPoster", function (data) {
        if (data && data.payload) {
            window.setBookPoster(data.payload.id, data.payload.dataUri);
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

    window.setBookPoster = function (bookId, dataUri) {
        // Work keys contain slashes — match the exact attribute value.
        const card = grid.querySelector(`.book-card[data-book-id="${CSS.escape(bookId)}"]`);
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

    function renderContent(items, contentType) {
        grid.innerHTML = "";
        const label = { movie: "movies", game: "games", music: "albums", book: "books" }[contentType];
        if (!items || items.length === 0) {
            grid.innerHTML = `
                <div class="empty-state">
                    <p style="color:#ff6b6b; margin:0;">No ${label} found.</p>
                </div>`;
            return;
        }
        const builders = { music: albumCard, book: bookCard };
        const builder = builders[contentType] || movieOrGameCard;
        items.forEach((item, idx) => {
            grid.appendChild(builder(item, idx, contentType));
        });
    }

    // Movie and game cards share the poster-grid shape (portrait 2:3 artwork,
    // title, year/genre/rating meta line).
    function movieOrGameCard(item, idx, contentType) {
        const isGame = contentType === "game";
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

        wireCardCommon(card, title, year, item.overview || "");
        return card;
    }

    // Album cards use their own shape: square cover art (album art is 1:1),
    // the artist as the subtitle line, and type/tags instead of a rating —
    // MusicBrainz has no numeric ratings.
    function albumCard(album, idx) {
        const card = document.createElement("div");
        card.className = "movie-card music-card";
        card.dataset.musicId = album.id || "";
        card.style.setProperty("--i", idx);

        const placeholderSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="10"></circle>
            <circle cx="12" cy="12" r="3"></circle>
        </svg>`;

        const year = album.year || "–";
        const subtitle = [album.primaryType, album.genre].filter(Boolean).join(" · ");

        card.innerHTML = `
            <div class="card-cover">
                <img class="poster-img hidden" alt="${escapeHtml(album.title)} cover"
                     loading="lazy" decoding="async">
                <div class="poster-placeholder">${placeholderSvg}</div>
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

        wireCardCommon(card, album.title, year,
            [album.artist, subtitle].filter(Boolean).join(" — "));
        return card;
    }

    // Book cards keep the portrait cover (book covers are 2:3-ish) but swap
    // the genre for the author as the subtitle line and show the Open Library
    // rating when one exists. The genre line carries the first community
    // subjects — Open Library has no curated genre field.
    function bookCard(book, idx) {
        const card = document.createElement("div");
        card.className = "movie-card book-card";
        card.dataset.bookId = book.id || "";
        card.style.setProperty("--i", idx);

        const placeholderSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
        </svg>`;

        const year = book.year || "–";
        const rating = book.rating ? book.rating.toFixed(1) : "–";
        const genre = book.genre || "";

        card.innerHTML = `
            <div class="card-poster">
                <img class="poster-img hidden" alt="${escapeHtml(book.title)} cover"
                     loading="lazy" decoding="async">
                <div class="poster-placeholder">${placeholderSvg}</div>
            </div>
            <div class="card-info">
                <div class="card-title">${escapeHtml(book.title || "Untitled")}</div>
                <div class="card-subtitle">${escapeHtml(book.author || "Unknown Author")}</div>
                <div class="card-meta">
                    <span class="card-meta-item">${escapeHtml(year)}</span>
                    <span class="card-meta-item">${escapeHtml(genre)}</span>
                    <span class="card-meta-item card-rating">★ ${rating}</span>
                </div>
            </div>
        `;

        wireCardCommon(card, book.title, year, book.overview || "");
        return card;
    }

    function wireCardCommon(card, title, year, detailText) {
        card.querySelector(".poster-img").addEventListener("error", function () {
            posterFailed(this);
        });
        card.addEventListener("click", () => {
            alert(`Selected: ${title} (${year})\n\n${escapeHtml(detailText || "")}`);
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

    async function requestPageContent(kind, term) {
        if (!window.krema) {
            showError("API bridge not ready yet. Please wait a moment and try again.");
            return;
        }
        const domain = DOMAINS[selectedContent];
        if (!domain) return;
        try {
            sectionTitle.textContent = kind === "search"
                ? "Search Results"
                : domain.trendingTitle;
            showLoading();
            // Kicks off the IO-pool fetch and resolves immediately with the
            // request's generation — the UI thread never blocks on network.
            const generation = await window.krema.invoke(
                domain.request,
                { kind: kind, query: term }
            );
            const reply = await pollDataUntilDone(domain.data, generation);
            if (!reply) return;   // superseded by a newer request, or already shown
            renderContent(reply.items, selectedContent);
            pollPostersUntilDone(domain.posters);
        } catch (e) {
            const message = e && e.message ? e.message : String(e);
            showError("Failed to load content: " + escapeHtml(message));
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
            sectionTitle.textContent = `${value.charAt(0).toUpperCase() + value.slice(1)}`
                + " (not connected)";
            grid.innerHTML = `
                <div class="empty-state">
                    <p>${escapeHtml(value.charAt(0).toUpperCase() + value.slice(1))} results are not yet connected to the API.</p>
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
