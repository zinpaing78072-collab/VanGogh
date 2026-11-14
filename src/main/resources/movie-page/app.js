/* Van Gogh content browser (Krema system-webview shell / JCEF experiment shell) */

/* The page talks to Java through window.krema:
   - await window.krema.invoke("requestMovies", { kind, query }) → movie list
   - await window.krema.invoke("requestGames", { kind, query }) → game list
   - await window.krema.invoke("pollGamePosters", {}) → game poster drain progress
   - window.krema.on("poster", data) / on("gamePoster", data) → poster data URIs
   The JCEF shell serves this page unchanged: jcef/krema-shim.js implements the
   same window.krema API on top of window.cefQuery.

   Poster delivery: movies stream as direct "poster" events pushed from the
   backend's IO pool — verified to work from background threads on macOS and in
   the JCEF shell (whose emitter marshals via the Swing EDT). On the Krema shell
   this is macOS-only by design; the Windows engine.eval note in AGENTS.md is
   why the games slice drains through pollGamePosters instead, which doubles as
   the completion signal (returns -1 when nothing is pending — events alone
   cannot tell the page "no more will come").
*/

document.addEventListener("DOMContentLoaded", async () => {
    const grid = document.getElementById("movieGrid");
    const searchInput = document.getElementById("searchInput");
    const searchBtn = document.getElementById("searchBtn");
    const contentFilter = document.getElementById("contentFilter");
    const sectionTitle = document.getElementById("sectionTitle");

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

    function renderContent(items, contentType) {
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
        items.forEach(item => {
            const title = item.title || item.game_title || "Untitled";
            const year = item.year || (item.release_date ? item.release_date.slice(0, 4) : "–");
            const card = document.createElement("div");
            card.className = isGame ? "movie-card game-card" : "movie-card";
            card.dataset[isGame ? "gameId" : "movieId"] = item.id;

            const placeholderSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                <line x1="3" y1="12" x2="21" y2="12"></line>
            </svg>`;

            const rating = item.rating ? item.rating.toFixed(1) : "–";
            const genre = item.genre || "";

            card.innerHTML = `
                <div class="card-poster">
                    <img class="poster-img hidden" alt="${escapeHtml(title)} poster">
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

    async function requestPageContent(kind, term) {
        if (!window.krema) {
            showError("API bridge not ready yet. Please wait a moment and try again.");
            return;
        }
        try {
            const isGame = contentFilter.value === "game";
            sectionTitle.textContent = kind === "search"
                ? "Search Results"
                : `Trending ${isGame ? "Games" : "Movies"}`;
            const items = await window.krema.invoke(
                isGame ? "requestGames" : "requestMovies",
                { kind: kind, query: term }
            );
            if (items && items.error) {
                showError(items.error);
                return;
            }
            renderContent(items, contentFilter.value);
            if (isGame) {
                pollGamePostersUntilDone();
            }
            // Movies now receive posters via direct "poster" events (Vavr Future).
        } catch (e) {
            const message = e && e.message ? e.message : String(e);
            showError("Failed to load content: " + escapeHtml(message));
        }
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
        if (value === "movie" || value === "game") {
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

    async function pollGamePostersUntilDone() {
        while (window.krema) {
            let pending;
            try {
                pending = await window.krema.invoke("pollGamePosters", {});
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
