/* Van Gogh — Movie Page JavaScript */

const TRENDING_MOVIES = [
    {
        title: "Forrest Gump",
        year: 1994,
        rating: 8.8,
        genre: "Drama · Romance",
        poster: "posters/forrest_gump.jpg",
        overview: "A man with a low IQ has accomplished great things in his life and been present during significant historic events."
    },
    {
        title: "Inception",
        year: 2010,
        rating: 8.8,
        genre: "Action · Sci-Fi · Thriller",
        poster: "posters/inception.jpg",
        overview: "A thief who steals corporate secrets through dream-sharing technology is given the inverse task of planting an idea."
    },
    {
        title: "Interstellar",
        year: 2014,
        rating: 8.6,
        genre: "Adventure · Drama · Sci-Fi",
        poster: "posters/interstellar.jpg",
        overview: "A team of explorers travel through a wormhole in space in an attempt to ensure humanity's survival."
    },
    {
        title: "Pulp Fiction",
        year: 1994,
        rating: 8.9,
        genre: "Crime · Drama",
        poster: "posters/pulp_fiction.jpg",
        overview: "The lives of two mob hitmen, a boxer, a gangster and his wife intertwine in four tales of violence."
    },
    {
        title: "The Shawshank Redemption",
        year: 1994,
        rating: 9.3,
        genre: "Drama",
        poster: "posters/shawshank.jpg",
        overview: "Two imprisoned men bond over a number of years, finding solace and eventual redemption through acts of common decency."
    },
    {
        title: "The Godfather",
        year: 1972,
        rating: 9.2,
        genre: "Crime · Drama",
        poster: "posters/godfather.jpg",
        overview: "The aging patriarch of an organized crime dynasty transfers control of his clandestine empire to his reluctant son."
    },
    {
        title: "The Dark Knight",
        year: 2008,
        rating: 9.0,
        genre: "Action · Crime · Drama",
        poster: "posters/dark_knight.jpg",
        overview: "When the menace known as the Joker wreaks havoc on Gotham, Batman must accept one of the greatest psychological tests."
    },
    {
        title: "The Matrix",
        year: 1999,
        rating: 8.7,
        genre: "Action · Sci-Fi",
        poster: null,
        overview: "A computer hacker learns from mysterious rebels about the true nature of his reality and his role in the war against its controllers."
    }
];

document.addEventListener("DOMContentLoaded", () => {
    const grid = document.getElementById("movieGrid");
    const searchInput = document.getElementById("searchInput");
    const searchBtn = document.getElementById("searchBtn");
    const contentFilter = document.getElementById("contentFilter");

    function renderMovies(movies) {
        grid.innerHTML = "";

        if (movies.length === 0) {
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

            const posterHtml = movie.poster
                ? `<img src="${movie.poster}" alt="${movie.title} poster" onerror="this.style.display='none'; this.parentElement.querySelector('.poster-placeholder').style.display='flex';">`
                : `<div class="poster-placeholder">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                            <line x1="3" y1="12" x2="21" y2="12"></line>
                        </svg>
                        <span>Poster</span>
                   </div>`;

            card.innerHTML = `
                <div class="card-poster">
                    ${posterHtml}
                </div>
                <div class="card-info">
                    <div class="card-title">${escapeHtml(movie.title)}</div>
                    <div class="card-meta">
                        <span class="card-meta-item">${movie.year}</span>
                        <span class="card-meta-item">${movie.genre}</span>
                        <span class="card-meta-item card-rating">★ ${movie.rating}</span>
                    </div>
                </div>
            `;

            card.addEventListener("click", () => {
                alert(`Selected: ${movie.title} (${movie.year})\n\n${movie.overview}`);
            });

            grid.appendChild(card);
        });
    }

    function escapeHtml(text) {
        const div = document.createElement("div");
        div.textContent = text;
        return div.innerHTML;
    }

    function search() {
        const term = searchInput.value.toLowerCase().trim();
        const filter = contentFilter.value;

        if (term === "") {
            renderMovies(TRENDING_MOVIES);
            return;
        }

        const filtered = TRENDING_MOVIES.filter(m =>
            m.title.toLowerCase().includes(term)
        );
        renderMovies(filtered);
    }

    searchBtn.addEventListener("click", search);
    searchInput.addEventListener("keypress", (e) => {
        if (e.key === "Enter") search();
    });

    contentFilter.addEventListener("change", () => {
        // Switch content type — for now just re-render trending
        renderMovies(TRENDING_MOVIES);
    });

    // Initial render: trending movies
    renderMovies(TRENDING_MOVIES);
});