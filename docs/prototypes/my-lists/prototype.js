/* Shared behaviour for the My Lists prototypes.
 *
 * No IPC, no backend, no storage: the sample library in data.js is the whole
 * state and every interaction is local to the page. The pages exist to compare
 * layout directions and to make possible features tangible — see README.md for
 * which parts are decided and which are speculation.
 */
(function () {
    "use strict";

    /* ------------------------------------------------------------- icons */

    const ICONS = {
        search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
        star: '<path d="m12 3 2.7 5.6 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/>',
        close: '<path d="M6 6l12 12M18 6 6 18"/>',
        film: '<rect x="2" y="4" width="20" height="16" rx="2"/><path d="M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5"/>',
        music: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
        book: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>',
        game: '<rect x="2" y="6" width="20" height="12" rx="4"/><path d="M6.5 12h4M8.5 10v4"/><path d="M16 10.5h.01M18.5 13.5h.01"/>',
        list: '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
        heart: '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l8.8 8.6 8.8-8.6a5.5 5.5 0 0 0 0-7.8z"/>',
        sparkles: '<path d="m12 3 1.6 4.4L18 9l-4.4 1.6L12 15l-1.6-4.4L6 9l4.4-1.6z"/><path d="m19 15 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"/>',
        arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
        chevron: '<path d="m6 9 6 6 6-6"/>',
        clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
        grid: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/>',
        rows: '<path d="M4 6h16M4 12h16M4 18h16"/>',
        plus: '<path d="M12 5v14M5 12h14"/>',
        download: '<path d="M12 4v11M8 11l4 4 4-4"/><path d="M4 20h16"/>',
        check: '<path d="m4 12 5 5L20 6"/>',
        info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
        trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14"/>',
        play: '<path d="m8 5 11 7-11 7z"/>',
        folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
        bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/>',
        user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-1a8 8 0 0 1 16 0v1"/>',
        home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/>',
        settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
        sidebar: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/>',
    };

    function icon(name) {
        return (
            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
            'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
            (ICONS[name] || "") +
            "</svg>"
        );
    }

    function formIcon(form) {
        return icon(form);
    }

    function esc(value) {
        return String(value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    function shortDate(iso) {
        const date = new Date(iso + "T00:00:00");
        return date.toLocaleDateString("en-US", { month: "short", day: "numeric" });
    }

    function listText(item) {
        const labels = { watchlist: "Watchlist", favorites: "Favorites" };
        return item.lists.map((key) => labels[key]).join(" · ");
    }

    function isFavorite(item) {
        return item.lists.indexOf("favorites") !== -1;
    }

    function toggleFavorite(item) {
        const index = item.lists.indexOf("favorites");
        if (index === -1) item.lists.push("favorites");
        else item.lists.splice(index, 1);
    }

    function removeItem(item) {
        const index = window.LIBRARY.indexOf(item);
        if (index !== -1) window.LIBRARY.splice(index, 1);
    }

    function byId(id) {
        return window.LIBRARY.find((item) => item.id === id);
    }

    function countByForm(items) {
        const counts = { movie: 0, music: 0, book: 0, game: 0 };
        items.forEach((item) => {
            counts[item.form] += 1;
        });
        return counts;
    }

    /* ------------------------------------------------------------- toast */

    let toastTimer = null;

    function toast(message) {
        let node = document.getElementById("mlToast");
        if (!node) {
            node = document.createElement("div");
            node.id = "mlToast";
            node.className = "ml-toast";
            node.setAttribute("role", "status");
            document.body.appendChild(node);
        }
        node.textContent = message;
        node.classList.add("show");
        window.clearTimeout(toastTimer);
        toastTimer = window.setTimeout(() => node.classList.remove("show"), 2600);
    }

    /* ------------------------------------------------------------- shell */

    function setupShell() {
        const sidebar = document.getElementById("sidebar");
        const toggle = document.getElementById("sidebarToggle");
        if (sidebar && toggle) {
            toggle.addEventListener("click", () => {
                sidebar.classList.toggle("collapsed");
                const collapsed = sidebar.classList.contains("collapsed");
                toggle.setAttribute("aria-expanded", String(!collapsed));
                toggle.setAttribute("aria-label", collapsed ? "Expand sidebar" : "Collapse sidebar");
            });
            const narrow = window.matchMedia("(max-width: 900px)");
            const applyNarrow = () => {
                if (narrow.matches) sidebar.classList.add("collapsed");
            };
            applyNarrow();
            if (narrow.addEventListener) narrow.addEventListener("change", applyNarrow);
        }

        const aiToggle = document.getElementById("aiToggle");
        if (aiToggle) {
            aiToggle.addEventListener("click", () => {
                const on = aiToggle.getAttribute("aria-pressed") !== "true";
                aiToggle.setAttribute("aria-pressed", String(on));
                aiToggle.title = on ? "AI recommendations on" : "AI recommendations off";
            });
        }

        const search = document.getElementById("searchInput");
        if (search) {
            const notice = () =>
                toast("Search runs in the real app — this prototype only browses the saved library.");
            search.addEventListener("keydown", (event) => {
                if (event.key === "Enter") notice();
            });
            const searchBtn = document.getElementById("searchBtn");
            if (searchBtn) searchBtn.addEventListener("click", notice);
        }
    }

    /* -------------------------------------------------------------- menus */

    function setupMenus() {
        document.querySelectorAll("[data-menu-trigger]").forEach((trigger) => {
            const menu = document.getElementById(trigger.getAttribute("data-menu-trigger"));
            if (!menu) return;
            trigger.addEventListener("click", (event) => {
                event.stopPropagation();
                menu.classList.toggle("hidden");
            });
            menu.addEventListener("click", (event) => event.stopPropagation());
            document.addEventListener("click", () => menu.classList.add("hidden"));
            menu.querySelectorAll("button").forEach((button) => {
                button.addEventListener("click", () => menu.classList.add("hidden"));
            });
        });
    }

    /* ------------------------------------------------------------ v1 shelf */

    function cardHTML(item, index) {
        const favorite = isFavorite(item);
        const inWatchlist = item.lists.indexOf("watchlist") !== -1;
        const square = item.form === "music";
        const cardClass = [
            "movie-card",
            "ml-card",
            item.form === "book" ? "book-card" : "",
            item.form === "game" ? "game-card" : "",
        ]
            .filter(Boolean)
            .join(" ");
        return (
            '<article class="' + cardClass + '" style="--i:' + index + '" ' +
            'data-id="' + item.id + '" data-form="' + item.form + '" ' +
            'data-lists="' + item.lists.join(" ") + '" data-title="' + esc(item.title.toLowerCase()) + '" ' +
            'data-saved="' + item.saved + '" tabindex="0" role="button" ' +
            'aria-label="Open ' + esc(item.title) + '">' +
            '<div class="ml-card-badges">' +
            (favorite ? '<span class="ml-pill favorite">' + icon("star") + "Favorite</span>" : "") +
            (inWatchlist ? '<span class="ml-pill status-want">Watchlist</span>' : "") +
            "</div>" +
            '<div class="ml-card-actions">' +
            '<button type="button" class="ml-card-action" data-act="favorite" aria-pressed="' + favorite + '" ' +
            'aria-label="' + (favorite ? "Remove from Favorites" : "Add to Favorites") + '" title="Favorite">' +
            icon("star") +
            "</button>" +
            '<button type="button" class="ml-card-action remove" data-act="remove" aria-label="Remove from My Lists" title="Remove">' +
            icon("close") +
            "</button>" +
            "</div>" +
            '<div class="' + (square ? "card-cover" : "card-poster") + '">' +
            '<img src="' + esc(item.art) + '" alt="' + esc(item.title) + '" loading="lazy" decoding="async">' +
            "</div>" +
            '<div class="card-info">' +
            '<div class="card-title">' + esc(item.title) + "</div>" +
            '<div class="card-meta">' +
            '<span class="card-meta-item">' + esc(String(item.year)) + "</span>" +
            '<span class="card-meta-item">' + esc(item.meta.split(" · ")[0]) + "</span>" +
            '<span class="card-meta-item card-rating">' + (item.rating === "—" ? "" : "★ " + esc(item.rating)) + "</span>" +
            "</div>" +
            '<div class="ml-saved">Saved ' + shortDate(item.saved) + " · " + listText(item) + "</div>" +
            "</div>" +
            "</article>"
        );
    }

    function setupShelf() {
        const grid = document.getElementById("libraryGrid");
        if (!grid) return;

        const params = new URLSearchParams(window.location.search);
        const startView = params.get("view");
        const state = {
            list: startView === "favorites" || startView === "watchlist" ? startView : "all",
            form: "all",
            query: "",
            sort: "recent",
        };

        const listButtons = Array.from(document.querySelectorAll("[data-list-filter]"));
        const formButtons = Array.from(document.querySelectorAll("[data-form-filter]"));
        const searchInput = document.getElementById("librarySearch");
        const sortLabel = document.getElementById("sortLabel");
        const summary = document.getElementById("librarySummary");
        const empty = document.getElementById("libraryEmpty");
        const sorters = {
            recent: (a, b) => b.saved.localeCompare(a.saved),
            oldest: (a, b) => a.saved.localeCompare(b.saved),
            title: (a, b) => a.title.localeCompare(b.title),
            form: (a, b) => a.form.localeCompare(b.form) || b.saved.localeCompare(a.saved),
        };
        const sortNames = { recent: "Newest saved", oldest: "Oldest saved", title: "Title A–Z", form: "Content form" };

        function matches(item) {
            if (state.list !== "all" && item.lists.indexOf(state.list) === -1) return false;
            if (state.form !== "all" && item.form !== state.form) return false;
            if (state.query) {
                const haystack = (item.title + " " + item.creator + " " + item.meta).toLowerCase();
                if (haystack.indexOf(state.query) === -1) return false;
            }
            return true;
        }

        function paint() {
            const items = window.LIBRARY.filter(matches).sort(sorters[state.sort]);
            grid.innerHTML = items.map(cardHTML).join("");
            empty.classList.toggle("hidden", items.length > 0);

            listButtons.forEach((button) => {
                button.setAttribute("aria-pressed", String(button.getAttribute("data-list-filter") === state.list));
            });
            formButtons.forEach((button) => {
                button.setAttribute("aria-pressed", String(button.getAttribute("data-form-filter") === state.form));
            });

            const favoriteCount = window.LIBRARY.filter(isFavorite).length;
            const watchCount = window.LIBRARY.filter((item) => item.lists.indexOf("watchlist") !== -1).length;
            summary.innerHTML =
                "<b>" + items.length + "</b> shown <span class=\"dot\"></span> " +
                "Library size <b>" + window.LIBRARY.length + "</b> <span class=\"dot\"></span> " +
                "Watchlist <b>" + watchCount + "</b> <span class=\"dot\"></span> " +
                "Favorites <b>" + favoriteCount + "</b>";

            document.querySelectorAll("[data-count-list]").forEach((node) => {
                const key = node.getAttribute("data-count-list");
                node.textContent = String(
                    key === "all"
                        ? window.LIBRARY.length
                        : window.LIBRARY.filter((item) => item.lists.indexOf(key) !== -1).length
                );
            });
            document.querySelectorAll("[data-count-form]").forEach((node) => {
                const key = node.getAttribute("data-count-form");
                node.textContent = String(
                    key === "all" ? window.LIBRARY.length : window.LIBRARY.filter((item) => item.form === key).length
                );
            });

            if (sortLabel) sortLabel.textContent = sortNames[state.sort];
        }

        listButtons.forEach((button) => {
            button.addEventListener("click", () => {
                state.list = button.getAttribute("data-list-filter");
                paint();
            });
        });
        formButtons.forEach((button) => {
            button.addEventListener("click", () => {
                state.form = button.getAttribute("data-form-filter");
                paint();
            });
        });
        document.querySelectorAll("[data-sort]").forEach((button) => {
            button.addEventListener("click", () => {
                state.sort = button.getAttribute("data-sort");
                document.querySelectorAll("[data-sort]").forEach((other) => {
                    other.setAttribute("aria-checked", String(other === button));
                });
                paint();
            });
        });
        if (searchInput) {
            searchInput.addEventListener("input", () => {
                state.query = searchInput.value.trim().toLowerCase();
                paint();
            });
        }
        grid.addEventListener("click", (event) => {
            const action = event.target.closest("[data-act]");
            const card = event.target.closest(".ml-card");
            if (action && card) {
                event.stopPropagation();
                const item = byId(card.getAttribute("data-id"));
                if (!item) return;
                if (action.getAttribute("data-act") === "favorite") {
                    toggleFavorite(item);
                    toast(isFavorite(item) ? "Added to Favorites — this feeds the AI engine." : "Removed from Favorites.");
                } else {
                    removeItem(item);
                    toast("Removed from My Lists.");
                }
                paint();
                return;
            }
            if (card) {
                const item = byId(card.getAttribute("data-id"));
                toast("Opening “" + item.title + "” — the detail view belongs to the content-form slice.");
            }
        });
        grid.addEventListener("keydown", (event) => {
            if (event.key === "Enter" || event.key === " ") {
                const card = event.target.closest(".ml-card");
                if (card && event.target === card) {
                    event.preventDefault();
                    card.click();
                }
            }
        });

        paint();
    }

    /* ----------------------------------------------------------- v2 ledger */

    function setupLedger() {
        const body = document.getElementById("ledgerBody");
        if (!body) return;

        const state = {
            view: "all",
            query: "",
            sort: "saved",
            dir: -1,
            compact: false,
            selected: new Set(),
        };
        const railButtons = Array.from(document.querySelectorAll("[data-view]"));
        const search = document.getElementById("ledgerSearch");
        const selectAll = document.getElementById("selectAll");
        const bulkbar = document.getElementById("bulkbar");
        const bulkCount = document.getElementById("bulkCount");
        const foot = document.getElementById("tableFoot");
        const panel = document.getElementById("tablePanel");

        function visibleItems() {
            let items = window.LIBRARY.slice();
            if (state.view === "watchlist" || state.view === "favorites") {
                items = items.filter((item) => item.lists.indexOf(state.view) !== -1);
            } else if (state.view === "recent") {
                items = items.filter((item) => item.saved >= "2026-11-12");
            } else if (["movie", "music", "book", "game"].indexOf(state.view) !== -1) {
                items = items.filter((item) => item.form === state.view);
            }
            if (state.query) {
                items = items.filter((item) =>
                    (item.title + " " + item.creator + " " + item.meta).toLowerCase().indexOf(state.query) !== -1
                );
            }
            const order = {
                saved: (a, b) => a.saved.localeCompare(b.saved),
                title: (a, b) => a.title.localeCompare(b.title),
                form: (a, b) => a.form.localeCompare(b.form) || a.title.localeCompare(b.title),
            };
            return items.sort((a, b) => order[state.sort](a, b) * state.dir);
        }

        function rowHTML(item) {
            const square = item.form === "music";
            const favorite = isFavorite(item);
            const statusLabels = { want: "Want to", progress: "In progress", done: "Finished" };
            return (
                '<tr data-id="' + item.id + '" class="' + (state.selected.has(item.id) ? "selected" : "") + '">' +
                '<td class="cell-check"><input class="ml-check" type="checkbox" data-select="' + item.id + '" ' +
                (state.selected.has(item.id) ? "checked" : "") + ' aria-label="Select ' + esc(item.title) + '"></td>' +
                '<td class="cell-art"><div class="ml-thumb ' + (square ? "square" : "") + '">' +
                '<img src="' + esc(item.art) + '" alt="" loading="lazy" decoding="async"></div></td>' +
                '<td><span class="ml-form-tag" data-form="' + item.form + '">' + formIcon(item.form) +
                "<span>" + window.FORM_LABELS[item.form] + "</span></span></td>" +
                '<td><span class="ml-table-title">' + esc(item.title) + "</span>" +
                '<span class="ml-table-creator">' + esc(item.creator) + " · " + item.year + "</span></td>" +
                '<td class="cell-status"><span class="ml-pill status-' + item.status + '">' + statusLabels[item.status] + "</span></td>" +
                '<td class="cell-saved">' + shortDate(item.saved) + "</td>" +
                '<td class="cell-actions">' +
                '<button type="button" class="ml-icon-btn" data-row-act="favorite" aria-pressed="' + favorite + '" ' +
                'aria-label="Favorite ' + esc(item.title) + '" title="Favorite">' + icon("star") + "</button>" +
                '<button type="button" class="ml-icon-btn" data-row-act="remove" aria-label="Remove ' + esc(item.title) + '" title="Remove">' +
                icon("trash") + "</button></td>" +
                "</tr>"
            );
        }

        function paint() {
            const items = visibleItems();
            body.innerHTML = items.length
                ? items.map(rowHTML).join("")
                : '<tr><td colspan="7"><div class="ml-empty"><strong>Nothing in this view yet</strong>' +
                  "Save a card from any content form and it lands here.</div></td></tr>";

            railButtons.forEach((button) => {
                button.setAttribute("aria-pressed", String(button.getAttribute("data-view") === state.view));
            });

            document.querySelectorAll("[data-rail-count]").forEach((node) => {
                const key = node.getAttribute("data-rail-count");
                let count = window.LIBRARY.length;
                if (key === "watchlist" || key === "favorites") {
                    count = window.LIBRARY.filter((item) => item.lists.indexOf(key) !== -1).length;
                } else if (key === "recent") {
                    count = window.LIBRARY.filter((item) => item.saved >= "2026-11-12").length;
                } else if (["movie", "music", "book", "game"].indexOf(key) !== -1) {
                    count = window.LIBRARY.filter((item) => item.form === key).length;
                }
                node.textContent = String(count);
            });

            const favorites = window.LIBRARY.filter(isFavorite).length;
            foot.innerHTML =
                "<span>" + items.length + " of " + window.LIBRARY.length + " saved items</span>" +
                "<span>" + favorites + " favorites · 4 content forms · local demo data</span>";

            if (selectAll) {
                const ids = items.map((item) => item.id);
                selectAll.checked = ids.length > 0 && ids.every((id) => state.selected.has(id));
                selectAll.indeterminate =
                    !selectAll.checked && ids.some((id) => state.selected.has(id));
                selectAll.setAttribute("aria-label", selectAll.checked ? "Clear selection" : "Select all rows");
            }

            const selectedVisible = items.filter((item) => state.selected.has(item.id)).length;
            bulkbar.classList.toggle("show", state.selected.size > 0);
            bulkCount.innerHTML = "<b>" + state.selected.size + "</b> selected";

            document.querySelectorAll("[data-sort-col]").forEach((node) => {
                const arrow = node.querySelector(".arrow");
                if (!arrow) return;
                arrow.textContent = node.getAttribute("data-sort-col") === state.sort ? (state.dir === -1 ? "↓" : "↑") : "";
            });

            document.getElementById("densityBtn").setAttribute("aria-pressed", String(state.compact));
            document.getElementById("ledgerTable").classList.toggle("compact", state.compact);
            panel.dataset.visible = String(selectedVisible);
        }

        railButtons.forEach((button) => {
            button.addEventListener("click", () => {
                state.view = button.getAttribute("data-view");
                paint();
            });
        });
        if (search) {
            search.addEventListener("input", () => {
                state.query = search.value.trim().toLowerCase();
                paint();
            });
        }
        document.querySelectorAll("[data-sort-col]").forEach((node) => {
            node.addEventListener("click", () => {
                const key = node.getAttribute("data-sort-col");
                if (state.sort === key) state.dir *= -1;
                else {
                    state.sort = key;
                    state.dir = key === "saved" ? -1 : 1;
                }
                paint();
            });
        });
        if (selectAll) {
            selectAll.addEventListener("change", () => {
                const items = visibleItems();
                if (selectAll.checked) items.forEach((item) => state.selected.add(item.id));
                else items.forEach((item) => state.selected.delete(item.id));
                paint();
            });
        }
        body.addEventListener("change", (event) => {
            const box = event.target.closest("[data-select]");
            if (!box) return;
            if (box.checked) state.selected.add(box.getAttribute("data-select"));
            else state.selected.delete(box.getAttribute("data-select"));
            paint();
        });
        body.addEventListener("click", (event) => {
            const action = event.target.closest("[data-row-act]");
            if (!action) return;
            const row = action.closest("tr");
            const item = byId(row.getAttribute("data-id"));
            if (!item) return;
            if (action.getAttribute("data-row-act") === "favorite") {
                toggleFavorite(item);
                toast(isFavorite(item) ? "Added to Favorites." : "Removed from Favorites.");
            } else {
                state.selected.delete(item.id);
                removeItem(item);
                toast("Removed from My Lists.");
            }
            paint();
        });

        function bulk(act) {
            const items = Array.from(state.selected).map(byId).filter(Boolean);
            if (act === "remove") {
                items.forEach(removeItem);
                toast(items.length + " item" + (items.length === 1 ? "" : "s") + " removed.");
            } else {
                items.forEach((item) => {
                    if (item.lists.indexOf(act) === -1) item.lists.push(act);
                });
                toast(items.length + " item" + (items.length === 1 ? "" : "s") + " added to " +
                    (act === "favorites" ? "Favorites." : "Watchlist."));
            }
            state.selected.clear();
            paint();
        }
        document.getElementById("bulkFavorite").addEventListener("click", () => bulk("favorites"));
        document.getElementById("bulkWatchlist").addEventListener("click", () => bulk("watchlist"));
        document.getElementById("bulkRemove").addEventListener("click", () => bulk("remove"));
        document.getElementById("bulkClear").addEventListener("click", () => {
            state.selected.clear();
            paint();
        });
        document.getElementById("densityBtn").addEventListener("click", () => {
            state.compact = !state.compact;
            paint();
        });
        document.getElementById("exportBtn").addEventListener("click", () => {
            toast("Export is a proposal — a JSON/CSV sheet of the saved library.");
        });
        document.getElementById("newListBtn").addEventListener("click", () => {
            toast("Custom lists are speculative — the decided model is Watchlist + Favorites.");
        });

        paint();
    }

    /* ----------------------------------------------------------- v3 studio */

    function miniRow(item, action) {
        const square = item.form === "music";
        return (
            '<div class="ml-mini-row">' +
            '<div class="ml-thumb ' + (square ? "square" : "") + '"><img src="' + esc(item.art) +
            '" alt="" loading="lazy" decoding="async"></div>' +
            '<div class="ml-mini-copy"><span class="ml-mini-title">' + esc(item.title) + "</span>" +
            '<span class="ml-mini-meta"><span>' + esc(item.creator) + "</span><span>" +
            window.FORM_LABELS[item.form] + "</span></span></div>" +
            (action || "") +
            "</div>"
        );
    }

    function setupStudio() {
        const bars = document.getElementById("tasteBars");
        if (!bars) return;

        const counts = countByForm(window.LIBRARY);
        const max = Math.max(counts.movie, counts.music, counts.book, counts.game, 1);
        bars.innerHTML = ["movie", "music", "book", "game"]
            .map(
                (form) =>
                    '<div class="ml-bar-row"><span>' + window.FORM_LABELS[form] + "</span>" +
                    '<span class="ml-bar"><i style="width:' + Math.round((counts[form] / max) * 100) + '%"></i></span>' +
                    '<span class="ml-bar-count">' + counts[form] + "</span></div>"
            )
            .join("");

        const favorites = window.LIBRARY.filter(isFavorite);
        const watchlist = window.LIBRARY.filter((item) => item.lists.indexOf("watchlist") !== -1);
        document.getElementById("statSaved").textContent = String(window.LIBRARY.length);
        document.getElementById("statWatchlist").textContent = String(watchlist.length);
        document.getElementById("statFavorites").textContent = String(favorites.length);
        document.getElementById("statForms").textContent = String(
            Object.values(countByForm(window.LIBRARY)).filter((value) => value > 0).length
        );

        document.getElementById("watchQueue").innerHTML = watchlist
            .sort((a, b) => b.saved.localeCompare(a.saved))
            .slice(0, 5)
            .map((item) =>
                miniRow(
                    item,
                    '<button type="button" class="ml-icon-btn" data-studio-fav="' + item.id + '" ' +
                        'aria-pressed="' + isFavorite(item) + '" title="Favorite" aria-label="Favorite ' +
                        esc(item.title) + '">' + icon("star") + "</button>"
                )
            )
            .join("");

        const progress = window.LIBRARY.filter((item) => item.status === "progress");
        document.getElementById("continueList").innerHTML = progress
            .slice(0, 4)
            .map((item) =>
                miniRow(
                    item,
                    '<button type="button" class="ml-icon-btn" data-studio-open="' + item.id + '" title="Open detail" aria-label="Open ' +
                        esc(item.title) + '">' + icon("arrow") + "</button>"
                )
            )
            .join("");

        const wall = favorites
            .sort((a, b) => b.saved.localeCompare(a.saved))
            .slice(0, 8)
            .map(
                (item) =>
                    '<figure class="' + (item.form === "music" ? "square" : "") + '">' +
                    '<img src="' + esc(item.art) + '" alt="' + esc(item.title) + '" loading="lazy">' +
                    "<figcaption>" + esc(item.title) + "</figcaption></figure>"
            )
            .join("");
        document.getElementById("favoritesWall").innerHTML = wall;

        const week = window.LIBRARY.slice()
            .sort((a, b) => b.saved.localeCompare(a.saved))
            .slice(0, 6);
        document.getElementById("weekTimeline").innerHTML = week
            .map(
                (item) =>
                    '<div class="ml-week-row"><span class="ml-week-date">' + shortDate(item.saved) + "</span>" +
                    '<span class="ml-week-marker"></span>' +
                    '<span class="ml-week-copy"><b>' + esc(item.title) + "</b><span>" +
                    window.FORM_LABELS[item.form] + " · " + esc(item.creator) + "</span></span></div>"
            )
            .join("");

        document.querySelectorAll("[data-studio-fav]").forEach((button) => {
            button.addEventListener("click", () => {
                const item = byId(button.getAttribute("data-studio-fav"));
                if (!item) return;
                toggleFavorite(item);
                button.setAttribute("aria-pressed", String(isFavorite(item)));
                toast(isFavorite(item) ? "Added to Favorites." : "Removed from Favorites.");
            });
        });
        document.querySelectorAll("[data-studio-open]").forEach((button) => {
            button.addEventListener("click", () => {
                const item = byId(button.getAttribute("data-studio-open"));
                if (item) toast("Opening “" + item.title + "” — the detail view is its own slice.");
            });
        });

        const draftBtn = document.getElementById("draftBtn");
        const draftLine = document.getElementById("draftLine");
        if (draftBtn) {
            draftBtn.addEventListener("click", () => {
                draftBtn.disabled = true;
                draftLine.textContent = "Composing picks from " + favorites.length + " favorites and " +
                    watchlist.length + " watchlist items…";
                window.setTimeout(() => {
                    draftLine.textContent =
                        "Draft ready: 6 films, 4 albums, 3 books, 3 games. Re-running composes a fresh set — the demo moment.";
                    draftBtn.disabled = false;
                    draftBtn.querySelector("span").textContent = "Re-run";
                }, 1100);
            });
        }
    }

    /* ------------------------------------------------------------ v4 board */

    function setupBoard() {
        const board = document.getElementById("board");
        if (!board) return;

        const state = { form: "all", favoritesOnly: false };
        const lanes = {
            want: document.getElementById("laneWant"),
            progress: document.getElementById("laneProgress"),
            done: document.getElementById("laneDone"),
        };
        const laneCounts = {
            want: document.getElementById("countWant"),
            progress: document.getElementById("countProgress"),
            done: document.getElementById("countDone"),
        };

        function matches(item) {
            if (state.form !== "all" && item.form !== state.form) return false;
            if (state.favoritesOnly && !isFavorite(item)) return false;
            return true;
        }

        function taskHTML(item) {
            const square = item.form === "music";
            return (
                '<article class="ml-task" draggable="true" data-task="' + item.id + '">' +
                '<div class="ml-thumb ' + (square ? "square" : "") + '"><img src="' + esc(item.art) +
                '" alt="" loading="lazy" decoding="async"></div>' +
                '<div class="ml-task-copy"><span class="ml-task-title">' + esc(item.title) + "</span>" +
                '<span class="ml-task-meta">' + esc(item.creator) + " · " + item.year + "</span>" +
                '<div class="ml-task-foot"><span class="ml-form-tag" data-form="' + item.form + '">' +
                formIcon(item.form) + "<span>" + window.FORM_LABELS[item.form] + "</span></span>" +
                '<span class="ml-task-actions">' +
                '<button type="button" class="ml-icon-btn" data-task-fav="' + item.id + '" aria-pressed="' +
                isFavorite(item) + '" title="Favorite" aria-label="Favorite ' + esc(item.title) + '">' +
                icon("star") + "</button>" +
                '<button type="button" class="ml-icon-btn" data-task-advance="' + item.id +
                '" title="Move to next status" aria-label="Move ' + esc(item.title) + ' to next status">' +
                icon("arrow") + "</button></span></div>" +
                (item.status === "progress"
                    ? '<div class="ml-progress"><i style="width:' + (item.progress || 40) + '%"></i></div>'
                    : "") +
                "</div></article>"
            );
        }

        function paint() {
            const statuses = ["want", "progress", "done"];
            statuses.forEach((status) => {
                const items = window.LIBRARY.filter((item) => item.status === status && matches(item)).sort(
                    (a, b) => b.saved.localeCompare(a.saved)
                );
                laneCounts[status].textContent = String(items.length);
                lanes[status].innerHTML = items.length
                    ? items.map(taskHTML).join("")
                    : '<div class="ml-lane-empty">Drop a card here</div>';
            });

            document.querySelectorAll("[data-board-form]").forEach((button) => {
                button.setAttribute(
                    "aria-pressed",
                    String(button.getAttribute("data-board-form") === state.form)
                );
            });
            document.getElementById("favoritesOnly").setAttribute("aria-pressed", String(state.favoritesOnly));
        }

        function move(item, status) {
            if (item.status === status) return;
            item.status = status;
            if (status === "done") item.progress = 100;
            if (status === "progress" && !item.progress) item.progress = 35;
            paint();
        }

        document.querySelectorAll("[data-board-form]").forEach((button) => {
            button.addEventListener("click", () => {
                state.form = button.getAttribute("data-board-form");
                paint();
            });
        });
        document.getElementById("favoritesOnly").addEventListener("click", () => {
            state.favoritesOnly = !state.favoritesOnly;
            paint();
        });

        board.addEventListener("click", (event) => {
            const fav = event.target.closest("[data-task-fav]");
            if (fav) {
                const item = byId(fav.getAttribute("data-task-fav"));
                if (!item) return;
                toggleFavorite(item);
                toast(isFavorite(item) ? "Added to Favorites." : "Removed from Favorites.");
                paint();
                return;
            }
            const advance = event.target.closest("[data-task-advance]");
            if (advance) {
                const item = byId(advance.getAttribute("data-task-advance"));
                if (!item) return;
                const order = ["want", "progress", "done"];
                move(item, order[(order.indexOf(item.status) + 1) % order.length]);
                return;
            }
            const task = event.target.closest("[data-task]");
            if (task) {
                const item = byId(task.getAttribute("data-task"));
                if (item) toast("Opening “" + item.title + "” — the detail view is its own slice.");
            }
        });

        let dragged = null;
        board.addEventListener("dragstart", (event) => {
            const task = event.target.closest("[data-task]");
            if (!task) return;
            dragged = byId(task.getAttribute("data-task"));
            task.classList.add("dragging");
            event.dataTransfer.effectAllowed = "move";
            event.dataTransfer.setData("text/plain", task.getAttribute("data-task"));
        });
        board.addEventListener("dragend", (event) => {
            const task = event.target.closest("[data-task]");
            if (task) task.classList.remove("dragging");
            board.querySelectorAll(".ml-lane").forEach((lane) => lane.classList.remove("drop-target"));
            dragged = null;
        });
        board.querySelectorAll(".ml-lane").forEach((lane) => {
            const status = lane.getAttribute("data-status");
            lane.addEventListener("dragover", (event) => {
                event.preventDefault();
                lane.classList.add("drop-target");
            });
            lane.addEventListener("dragleave", () => lane.classList.remove("drop-target"));
            lane.addEventListener("drop", (event) => {
                event.preventDefault();
                lane.classList.remove("drop-target");
                const id = dragged ? dragged.id : event.dataTransfer.getData("text/plain");
                const item = byId(id);
                if (item) {
                    move(item, status);
                    toast("Moved “" + item.title + "” to " +
                        { want: "Want to", progress: "In progress", done: "Finished" }[status] + ".");
                }
            });
        });

        paint();
    }

    /* ------------------------------------------------------------- v5 queue */

    function setupQueue() {
        const list = document.getElementById("queue");
        if (!list) return;

        const state = { status: "all", form: "all", favoritesOnly: false };
        const statusLabels = { all: "All saved", want: "Want to", progress: "In progress", done: "Finished" };
        const nextStatus = { want: "progress", progress: "done", done: "want" };
        const formButtons = Array.from(document.querySelectorAll("[data-queue-form]"));
        const statusButtons = Array.from(document.querySelectorAll("[data-status-filter]"));
        const label = document.getElementById("statusLabel");
        const summary = document.getElementById("queueSummary");
        const empty = document.getElementById("queueEmpty");
        const favoritesOnly = document.getElementById("favoritesOnly");

        function matches(item) {
            if (state.status !== "all" && item.status !== state.status) return false;
            if (state.form !== "all" && item.form !== state.form) return false;
            if (state.favoritesOnly && !isFavorite(item)) return false;
            return true;
        }

        function cardHTML(item, index) {
            const square = item.form === "music";
            const favorite = isFavorite(item);
            const next = nextStatus[item.status];
            return (
                '<article class="ml-qcard" data-status="' + item.status + '" data-id="' + item.id + '" ' +
                'style="--i:' + index + '" tabindex="0" role="button" aria-label="Open ' + esc(item.title) + '">' +
                '<div class="ml-qposter' + (square ? " square" : "") + '">' +
                '<img src="' + esc(item.art) + '" alt="' + esc(item.title) + '" loading="lazy" decoding="async"></div>' +
                '<div class="ml-qcopy">' +
                '<div class="ml-qtop">' +
                '<span class="ml-pill status-' + item.status + '">' + statusLabels[item.status] + "</span>" +
                (favorite ? '<span class="ml-pill favorite">' + icon("star") + "Favorite</span>" : "") +
                "</div>" +
                '<div class="ml-qtitle">' + esc(item.title) + "</div>" +
                '<div class="ml-qmeta">' + esc(item.creator) + " · " + esc(String(item.year)) + " · " +
                esc(item.meta) + "</div>" +
                (item.note ? '<p class="ml-qnote">' + esc(item.note) + "</p>" : "") +
                '<div class="ml-qfoot"><span>' + window.FORM_LABELS[item.form] + "</span>" +
                '<span class="dot"></span><span>Saved ' + shortDate(item.saved) + "</span></div>" +
                (item.status === "progress"
                    ? '<div class="ml-qprogress"><i style="width:' + (item.progress || 40) + '%"></i></div>'
                    : "") +
                "</div>" +
                '<div class="ml-qactions">' +
                '<button type="button" class="ml-icon-btn" data-q-fav="' + item.id + '" aria-pressed="' + favorite + '" ' +
                'title="Favorite" aria-label="' + (favorite ? "Remove from Favorites" : "Add to Favorites") + '">' +
                icon("star") + "</button>" +
                '<button type="button" class="ml-icon-btn" data-q-advance="' + item.id + '" ' +
                'title="Move to ' + statusLabels[next] + '" aria-label="Move ' + esc(item.title) + " to " +
                statusLabels[next] + '">' + icon("arrow") + "</button>" +
                '<button type="button" class="ml-icon-btn" data-q-remove="' + item.id + '" title="Remove" ' +
                'aria-label="Remove ' + esc(item.title) + ' from My Lists">' + icon("trash") + "</button>" +
                "</div></article>"
            );
        }

        function paint() {
            const items = window.LIBRARY.filter(matches).sort((a, b) => b.saved.localeCompare(a.saved));
            list.innerHTML = items.map(cardHTML).join("");
            empty.classList.toggle("hidden", items.length > 0);

            const counts = { all: window.LIBRARY.length, want: 0, progress: 0, done: 0 };
            window.LIBRARY.forEach((item) => {
                if (counts[item.status] !== undefined) counts[item.status] += 1;
            });
            document.querySelectorAll("[data-status-count]").forEach((node) => {
                node.textContent = String(counts[node.getAttribute("data-status-count")]);
            });
            if (label) label.textContent = statusLabels[state.status];
            statusButtons.forEach((button) => {
                button.setAttribute(
                    "aria-checked",
                    String(button.getAttribute("data-status-filter") === state.status)
                );
            });
            formButtons.forEach((button) => {
                button.setAttribute("aria-pressed", String(button.getAttribute("data-queue-form") === state.form));
            });
            favoritesOnly.setAttribute("aria-pressed", String(state.favoritesOnly));

            summary.innerHTML =
                "<b>" + window.LIBRARY.length + "</b> saved <span class=\"dot\"></span> " +
                "<b>" + counts.want + "</b> want to <span class=\"dot\"></span> " +
                "<b>" + counts.progress + "</b> in progress <span class=\"dot\"></span> " +
                "<b>" + counts.done + "</b> finished";
        }

        statusButtons.forEach((button) => {
            button.addEventListener("click", () => {
                state.status = button.getAttribute("data-status-filter");
                paint();
            });
        });
        formButtons.forEach((button) => {
            button.addEventListener("click", () => {
                state.form = button.getAttribute("data-queue-form");
                paint();
            });
        });
        favoritesOnly.addEventListener("click", () => {
            state.favoritesOnly = !state.favoritesOnly;
            paint();
        });

        function move(item, status) {
            item.status = status;
            if (status === "done") item.progress = 100;
            if (status === "progress" && !item.progress) item.progress = 35;
            paint();
        }

        list.addEventListener("click", (event) => {
            const fav = event.target.closest("[data-q-fav]");
            if (fav) {
                const item = byId(fav.getAttribute("data-q-fav"));
                if (!item) return;
                toggleFavorite(item);
                toast(isFavorite(item) ? "Added to Favorites." : "Removed from Favorites.");
                paint();
                return;
            }
            const advance = event.target.closest("[data-q-advance]");
            if (advance) {
                const item = byId(advance.getAttribute("data-q-advance"));
                if (!item) return;
                const next = nextStatus[item.status];
                move(item, next);
                toast("Moved “" + item.title + "” to " + statusLabels[next] + ".");
                return;
            }
            const remove = event.target.closest("[data-q-remove]");
            if (remove) {
                const item = byId(remove.getAttribute("data-q-remove"));
                if (!item) return;
                removeItem(item);
                toast("Removed from My Lists.");
                paint();
                return;
            }
            const card = event.target.closest(".ml-qcard");
            if (card) {
                const item = byId(card.getAttribute("data-id"));
                if (item) toast("Opening “" + item.title + "” — the detail view belongs to the content-form slice.");
            }
        });
        list.addEventListener("keydown", (event) => {
            if (event.key !== "Enter" && event.key !== " ") return;
            const card = event.target.closest(".ml-qcard");
            if (card && event.target === card) {
                event.preventDefault();
                card.click();
            }
        });

        paint();
    }

    /* ------------------------------------------------------------- startup */

    function init() {
        setupShell();
        setupMenus();
        setupShelf();
        setupLedger();
        setupStudio();
        setupBoard();
        setupQueue();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }

    window.MLPrototype = { icon: icon, toast: toast, shortDate: shortDate };
})();
