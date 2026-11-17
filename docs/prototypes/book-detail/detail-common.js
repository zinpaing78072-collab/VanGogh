/* Shared helpers for the Book detail prototypes: URL param handling, the
 * cover failover chain, star rendering, and the Book Card / header markup.
 * Not wired to the Krema backend — pages render straight from books-data.js. */

function escapeHtml(s) {
    return String(s == null ? "" : s)
        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

/* ?book=OL1168083W → the row; falls back to the first row. */
function bookFromUrl() {
    var key = new URLSearchParams(window.location.search).get("book");
    var rows = window.VANGOGH_BOOK_ROWS;
    for (var i = 0; i < rows.length; i++) {
        if (rows[i].key === "/works/" + key || rows[i].key === key) {
            return window.toVanGoghBook(rows[i]);
        }
    }
    return window.toVanGoghBook(rows[0]);
}

/* The other trending rows, in order, minus the current one. */
function otherBooks(current) {
    return window.VANGOGH_BOOK_ROWS
        .map(window.toVanGoghBook)
        .filter(function (b) { return b.workKey !== current.workKey; });
}

/* Attach the direct → wsrv.nl failover chain to an <img>, mirroring the
 * app's artwork loading. Ends on the gradient placeholder. */
function attachCover(img, coverUrl) {
    var sources = window.coverSources(coverUrl);
    var i = 0;
    img.addEventListener("error", function () {
        if (i < sources.length) {
            img.src = sources[i++];
        } else {
            img.classList.add("hidden");
            var ph = img.parentElement.querySelector(".poster-placeholder");
            if (ph) ph.classList.remove("hidden");
        }
    });
    if (sources.length) {
        img.src = sources[0];
        i = 1;
        img.classList.remove("hidden");
        var ph0 = img.parentElement.querySelector(".poster-placeholder");
        if (ph0) ph0.classList.add("hidden");
    } else {
        img.classList.add("hidden");
        var ph = img.parentElement.querySelector(".poster-placeholder");
        if (ph) ph.classList.remove("hidden");
    }
}

/* Stars: rating (0–5) → ★ glyphs with the remainder dimmed. */
function starsHtml(rating) {
    if (!rating) return "";
    var full = Math.round(rating);
    var glyphs = "";
    for (var i = 0; i < 5; i++) {
        glyphs += '<span class="' + (i < full ? "lit" : "dim") + '">\u2605</span>';
    }
    return '<span class="stars">' +
        '<span class="glyphs">' + glyphs + '</span>' +
        '<span class="value">' + rating.toFixed(1) + '</span>' +
        '<span class="note">Open Library community rating</span>' +
        '</span>';
}

/* One design-system Book Card (the exact shape the app grid renders). */
function bookCardHtml(book, idx, href) {
    var coverSvg = '<svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
        '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>' +
        '<path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path></svg>';
    var rating = book.rating ? book.rating.toFixed(1) : "";
    var year = book.year || "\u2013";
    var authorPages = [book.author, book.pages ? book.pages + " pages" : ""]
        .filter(function (p) { return p; }).join(" \u00b7 ");

    return '' +
        '<a class="movie-card book-card" style="text-decoration:none;color:inherit" href="' + href + '" data-book-id="' + escapeHtml(book.workKey) + '">' +
        '  <div class="card-poster">' +
        '    <img class="poster-img hidden" alt="' + escapeHtml(book.title) + ' cover" loading="lazy" decoding="async">' +
        '    <div class="poster-placeholder">' + coverSvg + '</div>' +
        '  </div>' +
        '  <div class="card-info">' +
        '    <div class="card-title">' + escapeHtml(book.title) + '</div>' +
        '    <div class="card-meta">' +
        '      <span class="card-meta-item">' + escapeHtml(year) + '</span>' +
        (authorPages ? '<span class="card-meta-item">' + escapeHtml(authorPages) + '</span>' : "") +
        '    </div>' +
        (rating ? '<div class="card-meta"><span class="card-meta-item card-rating">\u2605 ' + rating + '</span></div>' : "") +
        '  </div>' +
        '</a>';
}

/* Wires covers for every card inside a container. */
function mountCards(container, hrefFor) {
    container.innerHTML = window.VANGOGH_BOOK_ROWS.map(function (row, i) {
        return bookCardHtml(window.toVanGoghBook(row), i, hrefFor(window.toVanGoghBook(row)));
    }).join("");
    container.querySelectorAll(".movie-card").forEach(function (card) {
        var key = card.dataset.bookId;
        var row = window.VANGOGH_BOOK_ROWS.find(function (r) {
            return r.key === "/works/" + key;
        });
        var img = card.querySelector(".poster-img");
        img.src = window.coverSources(window.toVanGoghBook(row).coverUrl)[0];
        img.classList.remove("hidden");
        img.addEventListener("error", function () {
            var sources = window.coverSources(window.toVanGoghBook(row).coverUrl);
            if (!img._try) img._try = 1;
            if (img._try < sources.length) { img.src = sources[img._try++]; }
            else {
                img.classList.add("hidden");
                card.querySelector(".poster-placeholder").classList.remove("hidden");
            }
        });
        card.style.setProperty("--i", [].indexOf.call(card.parentElement.children, card));
    });
}

/* Detail-page header: brand + (disabled-ish) search + Book filter + layout tabs. */
function renderHeader(layoutId) {
    var header = document.querySelector(".app-header");
    header.innerHTML =
        '<a class="brand" href="index.html"><span class="brand-mark" aria-hidden="true">\u2736</span>Van Gogh</a>' +
        '<div class="search-bar">' +
        '  <input type="text" class="search-input" placeholder="Search movies, books, music, games\u2026">' +
        '  <button class="search-btn" onclick="window.location.href=\'index.html\'">Search</button>' +
        '</div>' +
        '<div class="filter-wrapper"><div class="filter-select"><span>Book</span></div></div>' +
        '<nav class="layout-switch" aria-label="Prototype layout">' +
        '  <a href="layout-open-page.html' + window.location.search + '"' + (layoutId === "a" ? ' class="active"' : "") + '>A</a>' +
        '  <a href="layout-first-light.html' + window.location.search + '"' + (layoutId === "b" ? ' class="active"' : "") + '>B</a>' +
        '  <a href="layout-catalog-card.html' + window.location.search + '"' + (layoutId === "c" ? ' class="active"' : "") + '>C</a>' +
        '</nav>';
}

/* Subjects string → chips (the app joins the first 3 with " · "). */
function subjectChips(subjects) {
    if (!subjects) return "";
    return subjects.split(" \u00b7 ").map(function (s) {
        return '<span class="chip">' + escapeHtml(s) + '</span>';
    }).join("");
}
