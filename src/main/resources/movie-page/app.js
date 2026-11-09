/* Van Gogh — Movie Page JavaScript (TMDB-backed, Krema system-webview shell) */

/* The page talks to Java through window.krema:
   - await window.krema.invoke("requestMovies", { kind, query }) → movie list
   - window.krema.on("poster", data) → poster data URIs streamed in
   No JavaFX bridge shim needed anymore — Krema is the sole UI shell.
*/

document.addEventListener("DOMContentLoaded", async () => {
    const grid = document.getElementById("movieGrid");
    const searchInput = document.getElementById("searchInput");
    const searchBtn = document.getElementById("searchBtn");
    const contentFilter = document.getElementById("contentFilter");

    // ⚗️ TEMPORARY EXPERIMENT (fullscreen chrome): NSApp has no main menu in this
    // stack, so in native fullscreen the top-edge reveal (menu bar + traffic
    // lights) shows nothing. Install a menu bar and have the user retest.
    // Krema 0.3.2 installs it via NSApp.setMainMenu: (items will look disabled —
    // that's expected; this run only tests whether the bar appears on hover).
    try {
        if (window.krema) {
            await window.krema.invoke("menu:setApplicationMenu", {
                menu: [
                    {
                        label: "Van Gogh",
                        items: [
                            { role: "about" },
                            { type: "separator" },
                            { role: "hide" },
                            { role: "hide-others" },
                            { role: "unhide" },
                            { type: "separator" },
                            { role: "quit" },
                        ],
                    },
                    {
                        label: "Edit",
                        items: [
                            { role: "undo" },
                            { role: "redo" },
                            { type: "separator" },
                            { role: "cut" },
                            { role: "copy" },
                            { role: "paste" },
                            { role: "select-all" },
                        ],
                    },
                    {
                        label: "View",
                        items: [{ role: "toggle-fullscreen" }],
                    },
                    {
                        label: "Window",
                        items: [{ role: "minimize" }, { role: "zoom" }],
                    },
                ],
            });
        }
    } catch (e) {
        console.error("[Van Gogh] temporary menu experiment failed:", e);
    }
    // ⚗️ END TEMPORARY EXPERIMENT
    const fullscreenBtn = document.getElementById("fullscreenBtn");

    function escapeHtml(text) {
        if (text == null) return "";
        const div = document.createElement("div");
        div.textContent = text;
        return div.innerHTML;
    }

    // Posters are streamed in from Java's IO pool as "poster" events;
    // the payload arrives wrapped as { payload, timestamp } by Krema's bridge.
    window.krema.on("poster", function (data) {
        if (data && data.payload) {
            window.setPoster(data.payload.id, data.payload.dataUri);
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

    function renderMovies(movies) {
        grid.innerHTML = "";
        if (!movies || movies.length === 0) {
            grid.innerHTML = `
                <div class="empty-state">
                    <p style="color:#ff6b6b; margin:0;">No movies found.</p>
                </div>`;
            return;
        }
        movies.forEach(movie => {
            const card = document.createElement("div");
            card.className = "movie-card";
            card.dataset.movieId = movie.id;

            const placeholderSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                <line x1="3" y1="12" x2="21" y2="12"></line>
            </svg>`;

            const rating = movie.rating ? movie.rating.toFixed(1) : "–";
            const year = movie.year || "–";
            const genre = movie.genre || "";

            card.innerHTML = `
                <div class="card-poster">
                    <img class="poster-img hidden" alt="${escapeHtml(movie.title)} poster">
                    <div class="poster-placeholder">${placeholderSvg}</div>
                </div>
                <div class="card-info">
                    <div class="card-title">${escapeHtml(movie.title)}</div>
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
                alert(`Selected: ${movie.title} (${year})\n\n${escapeHtml(movie.overview || "")}`);
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

    async function requestPageMovies(kind, term) {
        if (!window.krema) {
            showError("API bridge not ready yet. Please wait a moment and try again.");
            return;
        }
        try {
            const movies = await window.krema.invoke("requestMovies", { kind: kind, query: term });
            if (movies && movies.error) {
                showError(movies.error);
                return;
            }
            renderMovies(movies);
        } catch (e) {
            const message = e && e.message ? e.message : String(e);
            showError("Failed to load movie data: " + escapeHtml(message));
        }
    }

    // Seed the initial trending render.
    await requestPageMovies("trending", "");

    searchBtn.addEventListener("click", () => {
        const term = searchInput.value.trim();
        if (term === "") {
            requestPageMovies("trending", "");
        } else {
            requestPageMovies("search", term);
        }
    });
    searchInput.addEventListener("keypress", (e) => {
        if (e.key === "Enter") {
            const term = searchInput.value.trim();
            if (term === "") {
                requestPageMovies("trending", "");
            } else {
                requestPageMovies("search", term);
            }
        }
    });

    contentFilter.addEventListener("change", () => {
        // Only Movie is wired to TMDB for now.
        if (contentFilter.value === "movie") {
            requestPageMovies("trending", "");
        } else {
            grid.innerHTML = `
                <div class="empty-state">
                    <p>${escapeHtml(contentFilter.value.charAt(0).toUpperCase() + contentFilter.value.slice(1))} results are not yet connected to the API.</p>
                </div>`;
        }
    });
    // ---- Fullscreen toggle ----
    // macOS native fullscreen hides the window title bar / traffic lights and
    // they don't reliably reappear on hover (AppKit/WebKit limitation).
    // This in-app button lets users enter/exit fullscreen without the title bar.
    async function updateFullscreenButton() {
        if (!window.krema || !fullscreenBtn) return;
        try {
            const fs = await window.krema.invoke("window:isFullscreen");
            fullscreenBtn.textContent = fs ? "⊘" : "⛶";
            fullscreenBtn.title = fs ? "Exit fullscreen (Esc)" : "Enter fullscreen";
            fullscreenBtn.ariaLabel = fs ? "Exit fullscreen" : "Enter fullscreen";
        } catch (e) {}
    }

    fullscreenBtn.addEventListener("click", async () => {
        if (!window.krema) return;
        try {
            await window.krema.invoke("window:toggleFullscreen");
            setTimeout(updateFullscreenButton, 300);
        } catch (e) {
            showError("Failed to toggle fullscreen: " + escapeHtml(String(e.message || e)));
        }
    });

    await updateFullscreenButton();
});
