/* Van Gogh — Movie Page JavaScript (TMDB-backed) */

/* PROTOTYPE (krema spike) — bridge shim so the SAME page runs under both shells:
   the JavaFX WebView exposes window.javaBridge; the Krema system-webview shell
   exposes window.krema (invoke returns a Promise, posters arrive via events).
   Remove once the spike verdict is captured. Commands resolve to WebKit's
   main-event-macrotask loop below — untouched outside this block. */
(function () {
    if (!window.krema) return; // JavaFX shell: nothing to shim

    // Posters stream in from Java's IO pool as "poster" events; the payload
    // arrives wrapped as { payload, timestamp } by the krema-bridge script.
    window.krema.on("poster", function (data) {
        if (data && data.payload) {
            window.setPoster(data.payload.id, data.payload.dataUri);
        }
    });

    window.javaBridge = {
        requestMovies: async function (kind, term) {
            try {
                // Resolves with the parsed movies array directly (async IPC path).
                // Routed through renderMoviesFromJava so the shared render code
                // and its error handling stay the only render path.
                const movies = await window.krema.invoke("requestMovies", { kind: kind, query: term });
                window.renderMoviesFromJava(JSON.stringify(movies));
            } catch (e) {
                const message = e && e.message ? e.message : String(e);
                window.renderMoviesFromJava(JSON.stringify({ error: message }));
            }
        },
    };

    // Seed the initial trending render (the JavaFX shell calls seedTrending() from Java).
    const seed = () => window.javaBridge.requestMovies("trending", "");
    if (document.readyState !== "loading") {
        seed();
    } else {
        document.addEventListener("DOMContentLoaded", seed);
    }
})();


document.addEventListener("DOMContentLoaded", () => {
    const grid = document.getElementById("movieGrid");
    const searchInput = document.getElementById("searchInput");
    const searchBtn = document.getElementById("searchBtn");
    const contentFilter = document.getElementById("contentFilter");

    function escapeHtml(text) {
        if (text == null) return "";
        const div = document.createElement("div");
        div.textContent = text;
        return div.innerHTML;
    }

    // Posters are streamed in by Java as data: URIs (VanGoghApp#streamPosters),
    // so <img> elements start with no src and the placeholder is visible. If an
    // img carrying a real src fails to load, fall back to the placeholder.
    function posterFailed(img) {
        img.removeAttribute("src");
        img.classList.add("hidden");
        const placeholder = img.parentElement.querySelector(".poster-placeholder");
        if (placeholder) {
            placeholder.classList.remove("hidden");
        }
    }

    // Push target called by Java (FX thread) once one poster finishes loading.
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
                    <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <circle cx="11" cy="8" r="7"></circle>
                        <path d="M21 21l-4.35-4.35A8 8 0 1 0 3 3l4.35 4.35"></path>
                    </svg>
                    <p>No results found</p>
                </div>
            `;
            return;
        }

        movies.forEach(movie => {
            const card = document.createElement("div");
            card.className = "movie-card";
            card.dataset.movieId = movie.id;

            const placeholderSvg = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                            <line x1="3" y1="12" x2="21" y2="12"></line>
                        </svg>
                        <span>Poster</span>`;

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
            </div>
        `;
    }

    function requestPageMovies(kind, term) {
        if (!window.javaBridge) {
            showError("API bridge not ready yet. Please wait a moment and try again.");
            return;
        }
        window.javaBridge.requestMovies(kind, term);
    }

    // Exposed target called by Java (VanGoghApp) with the movie list JSON.
    window.renderMoviesFromJava = function (jsonString) {
        try {
            const movies = JSON.parse(jsonString);
            if (movies && movies.error) {
                showError(movies.error);
                return;
            }
            renderMovies(movies);
        } catch (e) {
            showError("Failed to load movie data: " + escapeHtml(e.message));
        }
    };

    function search() {
        const term = searchInput.value.trim();
        if (term === "") {
            requestPageMovies("trending", "");
            return;
        }
        requestPageMovies("search", term);
    }

    searchBtn.addEventListener("click", search);
    searchInput.addEventListener("keypress", (e) => {
        if (e.key === "Enter") search();
    });

    contentFilter.addEventListener("change", () => {
        // Only Movie is wired to TMDB for now.
        if (contentFilter.value === "movie") {
            requestPageMovies("trending", "");
        } else {
            grid.innerHTML = `
                <div class="empty-state">
                    <p>${escapeHtml(contentFilter.value.charAt(0).toUpperCase() + contentFilter.value.slice(1))} results are not yet connected to the API.</p>
                </div>
            `;
        }
    });
});
