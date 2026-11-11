/* Van Gogh — Movie Page JavaScript (Stargazer skin · Prototype B)
   Tweaks vs Prototype A: skeleton shimmer pass on initial load, staggered
   fade-in entrance on every render (load + live filter), and the same
   behavior otherwise (live search, movie filter stub, poster fallback). */

const MOVIES = [
    { id: "dark_knight", poster: "dark_knight.jpg",   title: "The Dark Knight",           year: 2008, genre: "Action · Crime · Drama",              rating: 8.5,
      overview: "When the Joker unleashes chaos on Gotham City, Batman faces his greatest psychological and physical tests yet." },
    { id: "forrest_gump", poster: "forrest_gump.jpg", title: "Forrest Gump",              year: 1994, genre: "Comedy · Drama · Romance",            rating: 8.5,
      overview: "Forty years of American history through the eyes of a man with a big heart and a box of chocolates." },
    { id: "godfather",   poster: "godfather.jpg",     title: "The Godfather",             year: 1972, genre: "Crime · Drama",                       rating: 8.7,
      overview: "The aging patriarch of an organized-crime dynasty transfers control of his empire to his youngest son." },
    { id: "inception",   poster: "inception.jpg",     title: "Inception",                 year: 2010, genre: "Action · Science Fiction",            rating: 8.4,
      overview: "A thief who steals secrets from dreams must plant an idea in a target's subconscious — in a dream within a dream." },
    { id: "interstellar", poster: "interstellar.jpg", title: "Interstellar",              year: 2014, genre: "Adventure · Drama · Science Fiction", rating: 8.4,
      overview: "A team of explorers travel through a wormhole in search of a new home for humanity as Earth's last farms fail." },
    { id: "pulp_fiction", poster: "pulp_fiction.jpg", title: "Pulp Fiction",              year: 1994, genre: "Crime · Drama · Thriller",            rating: 8.5,
      overview: "Two hitmen, a boxer, a gangster's wife and a diner robbery collide across four warped Los Angeles stories." },
    { id: "shawshank",   poster: "shawshank.jpg",     title: "The Shawshank Redemption",  year: 1994, genre: "Drama · Crime",                       rating: 8.7,
      overview: "A banker wrongly convicted of murder finds hope and an unlikely friendship inside Shawshank State Prison." }
];

document.addEventListener("DOMContentLoaded", () => {
    const grid = document.getElementById("movieGrid");
    const searchInput = document.getElementById("searchInput");
    const searchBtn = document.getElementById("searchBtn");
    const contentFilter = document.getElementById("contentFilter");

    let initialLoadDone = false;
    let initialTimer = null;

    function escapeHtml(text) {
        if (text == null) return "";
        const div = document.createElement("div");
        div.textContent = text;
        return div.innerHTML;
    }

    // If a bundled poster file is missing, fall back to the placeholder.
    function posterFailed(img) {
        img.removeAttribute("src");
        img.classList.add("hidden");
        const placeholder = img.parentElement.querySelector(".poster-placeholder");
        if (placeholder) {
            placeholder.classList.remove("hidden");
        }
    }

    function moviesForQuery(term) {
        const needle = term.trim().toLowerCase();
        if (!needle) return MOVIES;
        return MOVIES.filter(m =>
            (m.title + " " + m.genre + " " + m.year + " " + m.overview).toLowerCase().includes(needle)
        );
    }

    // Prototype B: shimmering placeholder cards before the first render.
    function showSkeletons(count) {
        grid.innerHTML = "";
        for (let i = 0; i < count; i++) {
            const sk = document.createElement("div");
            sk.className = "movie-card skeleton";
            sk.innerHTML = `
                <div class="card-poster"></div>
                <div class="card-info">
                    <div class="sk-line" style="width:72%"></div>
                    <div class="sk-line" style="width:48%"></div>
                    <div class="sk-line" style="width:36%"></div>
                </div>`;
            grid.appendChild(sk);
        }
    }

    function settleInitialLoad() {
        if (initialLoadDone) return;
        initialLoadDone = true;
        clearTimeout(initialTimer);
    }

    function renderMovies(movies) {
        grid.innerHTML = "";
        if (!movies || movies.length === 0) {
            grid.innerHTML = `
                <div class="empty-state">
                    <p style="color:#F4D35E; margin:0;">No movies found.</p>
                </div>`;
            return;
        }
        movies.forEach((movie, idx) => {
            const card = document.createElement("div");
            card.className = "movie-card";
            card.dataset.movieId = movie.id;
            card.style.setProperty("--i", idx);   // stagger the entrance

            const placeholderSvg = `<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                <line x1="3" y1="12" x2="21" y2="12"></line>
            </svg>`;

            const rating = movie.rating ? movie.rating.toFixed(1) : "–";
            const year = movie.year || "–";
            const genre = movie.genre || "";

            card.innerHTML = `
                <div class="card-poster">
                    <img class="poster-img" src="posters/${movie.poster}" alt="${escapeHtml(movie.title)} poster">
                    <div class="poster-placeholder hidden">${placeholderSvg}</div>
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
                alert(`Selected: ${movie.title} (${year})\n\n${escapeHtml(movie.overview)}`);
            });

            grid.appendChild(card);
        });
    }

    // Seed the initial trending render: a short skeleton shimmer pass,
    // then the staggered fade-in. Skipped for reduced-motion users.
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) {
        initialLoadDone = true;
        renderMovies(MOVIES);
    } else {
        showSkeletons(MOVIES.length);
        initialTimer = setTimeout(() => {
            initialLoadDone = true;
            renderMovies(MOVIES);
        }, 750);
    }

    searchBtn.addEventListener("click", () => {
        settleInitialLoad();
        renderMovies(moviesForQuery(searchInput.value));
    });
    searchInput.addEventListener("keypress", (e) => {
        if (e.key === "Enter") {
            settleInitialLoad();
            renderMovies(moviesForQuery(searchInput.value));
        }
    });
    searchInput.addEventListener("input", () => {
        settleInitialLoad();
        renderMovies(moviesForQuery(searchInput.value));
    });

    contentFilter.addEventListener("change", () => {
        // Only Movie is wired here, matching the app's filter behavior.
        settleInitialLoad();
        if (contentFilter.value === "movie") {
            renderMovies(MOVIES);
        } else {
            grid.innerHTML = `
                <div class="empty-state">
                    <p>${escapeHtml(contentFilter.value.charAt(0).toUpperCase() + contentFilter.value.slice(1))} results are not yet connected to the API.</p>
                </div>`;
        }
    });
});
