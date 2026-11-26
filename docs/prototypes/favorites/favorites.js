/* Favorites prototype — shared shell, cards, peek drawer and interactions.
 *
 * Classic script on purpose: the prototypes open from a static server (or
 * file://) with no build step. Nothing here talks to the app; `window.krema`
 * is never touched.
 */
(function () {
  "use strict";

  /* ------------------------------------------------------------------ icons */
  var ICONS = {
    home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/>',
    music: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
    movie: '<rect x="2" y="4" width="20" height="16" rx="2"/><path d="M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5"/>',
    book: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>',
    game: '<rect x="2" y="6" width="20" height="12" rx="4"/><path d="M6.5 12h4M8.5 10v4"/><path d="M16 10.5h.01M18.5 13.5h.01"/>',
    lists: '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
    heart: '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l8.8 8.6 8.8-8.6a5.5 5.5 0 0 0 0-7.8z"/>',
    grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
    list: '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
    sort: '<path d="M11 5h10M11 9h7M11 13h4"/><path d="M3 17l3 3 3-3M6 18V4"/>',
    eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/>',
    check: '<path d="m20 6-11 11-5-5"/>',
    x: '<path d="M18 6 6 18M6 6l12 12"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    folder: '<path d="M4 20h16a1 1 0 0 0 1-1V7a1 1 0 0 0-1-1h-7l-2-2H4a1 1 0 0 0-1 1v14a1 1 0 0 0 1 1z"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6"/><path d="M10 11v5M14 11v5"/>',
    undo: '<path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-15-6.7L3 13"/>',
    spark: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M18.4 5.6l-2.8 2.8M8.4 15.6l-2.8 2.8"/>',
    tag: '<path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><circle cx="7.5" cy="7.5" r="1"/>',
    arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
    chevron: '<path d="m6 9 6 6 6-6"/>',
    sliders: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M1 14h6M9 8h6M17 16h6"/>',
    layers: '<path d="m12 2 9 5-9 5-9-5 9-5z"/><path d="m3 12 9 5 9-5M3 17l9 5 9-5"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>'
  };

  function icon(name, className) {
    return (
      '<svg class="' + (className || "i") + '" viewBox="0 0 24 24" fill="none" ' +
      'stroke="currentColor" stroke-width="1.8" stroke-linecap="round" ' +
      'stroke-linejoin="round" aria-hidden="true">' + (ICONS[name] || "") + "</svg>"
    );
  }

  /* ----------------------------------------------------------------- domains */
  var DOMAINS = {
    movie: { label: "Movies", short: "MOVIE", icon: "movie" },
    music: { label: "Music", short: "MUSIC", icon: "music" },
    book: { label: "Books", short: "BOOK", icon: "book" },
    game: { label: "Games", short: "GAME", icon: "game" }
  };
  var DOMAIN_ORDER = ["movie", "music", "book", "game"];
  var TODAY = new Date("2026-11-25T12:00:00");

  function esc(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function all() {
    return window.FAVORITES || [];
  }

  function getItem(id) {
    return all().filter(function (item) { return item.id === id; })[0] || null;
  }

  function collectionById(id) {
    return (window.COLLECTIONS || []).filter(function (c) { return c.id === id; })[0] || null;
  }

  function domainOf(item) {
    return DOMAINS[item.domain] || { label: "Item", short: "ITEM", icon: "spark" };
  }

  function artClass(item) {
    return item.domain === "music" ? "card-cover" : "card-poster";
  }

  function savedLabel(iso) {
    var date = new Date(iso);
    return "Saved " + date.toLocaleDateString("en-US", { month: "short", day: "numeric" });
  }

  function lastSavedLabel(items) {
    if (!items.length) return "";
    var latest = items.reduce(function (a, b) {
      return new Date(b.saved) > new Date(a.saved) ? b : a;
    });
    var hours = Math.floor((TODAY - new Date(latest.saved)) / 3600000);
    if (hours < 1) return "just now";
    if (hours < 24) return hours + "h ago";
    var days = Math.floor(hours / 24);
    return days === 1 ? "yesterday" : days + "d ago";
  }

  function ratingHTML(item) {
    if (!item.rating) return "";
    return '<span class="fav-meta-item fav-rating">★ ' + item.rating.toFixed(1) + "</span>";
  }

  function tagsHTML(item, limit) {
    var tags = (item.tags || []).slice(0, limit || 99);
    if (!tags.length) return "";
    return '<div class="fav-tags">' + tags.map(function (tag) {
      return '<span class="tag">' + esc(tag) + "</span>";
    }).join("") + "</div>";
  }

  /* ------------------------------------------------------------------- cards */
  function cardHTML(item, index, opts) {
    opts = opts || {};
    var d = domainOf(item);
    var collection = item.collection ? collectionById(item.collection) : null;
    var selected = opts.selectedIds && opts.selectedIds.indexOf(item.id) !== -1;
    return (
      '<article class="fav-card movie-card' + (selected ? " is-selected" : "") + '" ' +
        'data-domain="' + item.domain + '" data-id="' + esc(item.id) + '" data-fav-card ' +
        'aria-label="' + esc(item.title) + " — " + d.label + '" ' +
        (opts.draggable ? 'draggable="true" ' : "") +
        'tabindex="0" style="--i:' + index + '">' +
        '<div class="fav-art ' + artClass(item) + '">' +
          '<img src="' + esc(item.img) + '" alt="" loading="lazy" decoding="async">' +
          '<span class="domain-badge">' + d.short + "</span>" +
          '<button type="button" class="fav-heart" data-heart aria-pressed="true" ' +
            'aria-label="Remove ' + esc(item.title) + ' from favorites" title="Remove from favorites">' +
            icon("heart", "heart-icon") +
          "</button>" +
          (opts.selectable
            ? '<button type="button" class="fav-select" data-select aria-pressed="' + (selected ? "true" : "false") + '" ' +
              'aria-label="' + (selected ? "Deselect " : "Select ") + esc(item.title) + '">' + icon("check") + "</button>"
            : "") +
          '<div class="fav-actions">' +
            '<button type="button" class="mini-btn" data-peek>' + icon("eye") + " Peek</button>" +
          "</div>" +
        "</div>" +
        '<div class="fav-info">' +
          '<div class="fav-title">' + esc(item.title) + "</div>" +
          '<div class="fav-sub">' + esc(item.sub) + "</div>" +
          '<div class="fav-meta">' +
            '<span class="fav-meta-item">' + item.year + "</span>" +
            '<span class="fav-meta-item">' + esc(item.meta) + "</span>" +
            ratingHTML(item) +
          "</div>" +
          (opts.note === false ? "" : '<p class="fav-note">“' + esc(item.note) + '”</p>') +
          '<div class="fav-foot">' +
            "<span>" + savedLabel(item.saved) + "</span>" +
            (collection
              ? '<span class="fav-collection" style="--col:' + collection.colour + '">' +
                '<i aria-hidden="true"></i>' + esc(collection.title) + "</span>"
              : '<span class="fav-unfiled">Unfiled</span>') +
          "</div>" +
        "</div>" +
      "</article>"
    );
  }

  function rowHTML(item, index, opts) {
    opts = opts || {};
    var d = domainOf(item);
    var collection = item.collection ? collectionById(item.collection) : null;
    return (
      '<article class="fav-row' + (opts.className ? " " + opts.className : "") + '" data-domain="' + item.domain + '" data-id="' + esc(item.id) + '" data-fav-card tabindex="0" aria-label="' + esc(item.title) + " — " + d.label + '" style="--i:' + index + '">' +
        '<div class="fav-row-art ' + artClass(item) + '">' +
          '<img src="' + esc(item.img) + '" alt="" loading="lazy" decoding="async">' +
        "</div>" +
        '<div class="fav-row-main">' +
          '<div class="fav-row-eyebrow"><span class="domain-dot"></span>' + d.label +
            " · " + savedLabel(item.saved) + "</div>" +
          '<div class="fav-row-title">' + esc(item.title) + "</div>" +
          '<div class="fav-row-meta">' + esc(item.sub) + " · " + item.year + " · " + esc(item.meta) + "</div>" +
          '<p class="fav-row-note">“' + esc(item.note) + '”</p>' +
        "</div>" +
        '<div class="fav-row-side">' +
          (collection
            ? '<span class="fav-collection" style="--col:' + collection.colour + '"><i aria-hidden="true"></i>' +
              esc(collection.title) + "</span>"
            : "") +
          '<div class="fav-row-actions">' +
            '<button type="button" class="mini-btn" data-peek>' + icon("eye") + " Peek</button>" +
            '<button type="button" class="fav-heart inline" data-heart aria-pressed="true" ' +
              'aria-label="Remove ' + esc(item.title) + ' from favorites">' + icon("heart") + "</button>" +
          "</div>" +
        "</div>" +
      "</article>"
    );
  }

  function skeletonHTML(index) {
    return (
      '<div class="fav-card movie-card skeleton-card" aria-hidden="true" style="--i:' + index + '">' +
        '<div class="fav-art card-poster skeleton-poster"></div>' +
        '<div class="fav-info">' +
          '<div class="skeleton-line skeleton-title"></div>' +
          '<div class="skeleton-line skeleton-meta"></div>' +
        "</div>" +
      "</div>"
    );
  }

  function skeletonsHTML(count) {
    var out = "";
    for (var i = 0; i < (count || 10); i++) out += skeletonHTML(i);
    return out;
  }

  function emptyHTML(opts) {
    opts = opts || {};
    return (
      '<div class="fav-empty">' +
        '<div class="fav-empty-mark">' + icon("heart") + "</div>" +
        '<p class="fav-empty-kicker">' + esc(opts.kicker || "No signal") + "</p>" +
        "<h3>" + esc(opts.title || "Nothing saved here yet") + "</h3>" +
        "<p>" + esc(opts.body || "Favorites you keep from any form land in this one shelf.") + "</p>" +
        '<div class="fav-empty-actions">' +
          '<a class="nbtn primary" href="' + esc(opts.href || "index.html") + '">' +
            (opts.cta || "Back to the gallery") + icon("arrow") +
          "</a>" +
          (opts.secondary || "") +
        "</div>" +
      "</div>"
    );
  }

  /* ------------------------------------------------------------------- peek */
  function peekMarkup(item) {
    var d = domainOf(item);
    var collection = item.collection ? collectionById(item.collection) : null;
    return (
      '<div class="peek-head">' +
        '<div>' +
          '<p class="peek-eyebrow">Favorite // ' + d.label + "</p>" +
          "<h2>" + esc(item.title) + "</h2>" +
        "</div>" +
        '<button type="button" class="icon-btn" data-peek-close aria-label="Close preview">' + icon("x") + "</button>" +
      "</div>" +
      '<div class="peek-hero" data-domain="' + item.domain + '">' +
        '<div class="peek-art ' + artClass(item) + '"><img src="' + esc(item.img) + '" alt=""></div>' +
        '<div class="peek-facts">' +
          '<p class="peek-sub">' + esc(item.sub) + "</p>" +
          '<div class="fav-meta"><span class="fav-meta-item">' + item.year + "</span>" +
            '<span class="fav-meta-item">' + esc(item.meta) + "</span>" + ratingHTML(item) + "</div>" +
          '<div class="peek-saved">' + icon("clock") + savedLabel(item.saved) + "</div>" +
          (collection
            ? '<span class="fav-collection" style="--col:' + collection.colour + '"><i aria-hidden="true"></i>' +
              esc(collection.title) + "</span>"
            : '<span class="fav-unfiled">Unfiled</span>') +
        "</div>" +
      "</div>" +
      '<div class="peek-section">' +
        '<p class="peek-label">Why it is here</p>' +
        '<p class="peek-note">“' + esc(item.note) + '”</p>' +
      "</div>" +
      (item.tags && item.tags.length
        ? '<div class="peek-section"><p class="peek-label">Tags</p>' + tagsHTML(item) + "</div>"
        : "") +
      '<div class="peek-actions">' +
        '<button type="button" class="nbtn primary" data-open-detail>' + icon("arrow") + " Open detail</button>" +
        '<button type="button" class="nbtn danger" data-heart data-remove-peek>' + icon("trash") + " Remove</button>" +
      "</div>" +
      '<p class="peek-footnote">Prototype preview — the real peek opens the form\'s detail view.</p>'
    );
  }

  function ensurePeek() {
    if (document.getElementById("peekPanel")) return;
    var backdrop = document.createElement("div");
    backdrop.className = "peek-backdrop";
    backdrop.setAttribute("data-peek-close", "");
    var panel = document.createElement("aside");
    panel.className = "peek";
    panel.id = "peekPanel";
    panel.setAttribute("aria-hidden", "true");
    panel.setAttribute("aria-label", "Favorite preview");
    panel.innerHTML = '<div class="peek-body"></div>';
    document.body.appendChild(backdrop);
    document.body.appendChild(panel);
  }

  var peekId = null;

  function openPeek(id) {
    var item = getItem(id);
    if (!item) return;
    ensurePeek();
    peekId = id;
    document.querySelector("#peekPanel .peek-body").innerHTML = peekMarkup(item);
    document.body.classList.add("peek-open");
    document.getElementById("peekPanel").setAttribute("aria-hidden", "false");
  }

  function closePeek() {
    peekId = null;
    document.body.classList.remove("peek-open");
    var panel = document.getElementById("peekPanel");
    if (panel) panel.setAttribute("aria-hidden", "true");
  }

  /* ------------------------------------------------------------------ toast */
  var toastTimer = null;

  function toast(message, actionLabel, onAction) {
    var el = document.getElementById("vgToast");
    if (!el) {
      el = document.createElement("div");
      el.id = "vgToast";
      el.className = "toast";
      el.setAttribute("role", "status");
      el.setAttribute("aria-live", "polite");
      document.body.appendChild(el);
    }
    el.innerHTML =
      '<span class="toast-mark">' + icon("spark") + "</span>" +
      '<span class="toast-text">' + esc(message) + "</span>" +
      (actionLabel
        ? '<button type="button" class="toast-action" data-toast-action>' + esc(actionLabel) + "</button>"
        : "");
    var action = el.querySelector("[data-toast-action]");
    if (action) {
      action.addEventListener("click", function () {
        if (typeof onAction === "function") onAction();
        hideToast();
      }, { once: true });
    }
    requestAnimationFrame(function () { el.classList.add("show"); });
    clearTimeout(toastTimer);
    toastTimer = setTimeout(hideToast, actionLabel ? 6000 : 3200);
  }

  function hideToast() {
    var el = document.getElementById("vgToast");
    if (el) el.classList.remove("show");
    clearTimeout(toastTimer);
  }

  /* -------------------------------------------------------------- favourites */
  function emitChange(detail) {
    document.dispatchEvent(new CustomEvent("favorites:change", { detail: detail }));
  }

  function removeFavorite(id) {
    var list = all();
    var index = -1;
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === id) { index = i; break; }
    }
    if (index === -1) return null;
    var item = list.splice(index, 1)[0];
    emitChange({ type: "remove", item: item, index: index });
    toast("Removed “" + item.title + "” from favorites", "Undo", function () {
      list.splice(Math.min(index, list.length), 0, item);
      emitChange({ type: "restore", item: item, index: index });
      toast("Restored “" + item.title + "”");
    });
    return item;
  }

  function restoreFavorite(item, index) {
    var list = all();
    if (getItem(item.id)) return;
    list.splice(Math.min(index == null ? list.length : index, list.length), 0, item);
    emitChange({ type: "restore", item: item, index: index });
  }

  /* ------------------------------------------------------------- collection */
  function moveToCollection(id, collectionId) {
    var item = getItem(id);
    if (!item || item.collection === collectionId) return;
    var before = item.collection;
    item.collection = collectionId;
    emitChange({ type: "move", item: item, from: before, to: collectionId });
    var target = collectionId ? collectionById(collectionId) : null;
    toast(target ? "Moved to “" + target.title + "”" : "Moved to Unfiled");
  }

  /* ---------------------------------------------------------------- filters */
  function filterItems(items, filters) {
    filters = filters || {};
    var domain = filters.domain || "all";
    var query = (filters.query || "").trim().toLowerCase();
    var tag = filters.tag || "all";
    return items.filter(function (item) {
      if (domain !== "all" && item.domain !== domain) return false;
      if (tag !== "all" && (item.tags || []).indexOf(tag) === -1) return false;
      if (query) {
        var haystack = [
          item.title, item.sub, item.meta, item.note, String(item.year),
          (item.tags || []).join(" ")
        ].join(" ").toLowerCase();
        if (haystack.indexOf(query) === -1) return false;
      }
      return true;
    });
  }

  function sortItems(items, mode) {
    var copy = items.slice();
    switch (mode) {
      case "title":
        copy.sort(function (a, b) { return a.title.localeCompare(b.title); });
        break;
      case "year":
        copy.sort(function (a, b) { return b.year - a.year || a.title.localeCompare(b.title); });
        break;
      case "rating":
        copy.sort(function (a, b) {
          var ra = a.rating == null ? -1 : a.rating;
          var rb = b.rating == null ? -1 : b.rating;
          return rb - ra || a.title.localeCompare(b.title);
        });
        break;
      case "domain":
        copy.sort(function (a, b) {
          return DOMAIN_ORDER.indexOf(a.domain) - DOMAIN_ORDER.indexOf(b.domain) ||
            a.title.localeCompare(b.title);
        });
        break;
      default:
        copy.sort(function (a, b) { return new Date(b.saved) - new Date(a.saved); });
    }
    return copy;
  }

  function domainCounts(items) {
    var counts = { all: items.length, movie: 0, music: 0, book: 0, game: 0 };
    items.forEach(function (item) { counts[item.domain] = (counts[item.domain] || 0) + 1; });
    return counts;
  }

  function tagCounts(items) {
    var map = {};
    items.forEach(function (item) {
      (item.tags || []).forEach(function (tag) {
        map[tag] = (map[tag] || 0) + 1;
      });
    });
    return Object.keys(map).map(function (tag) {
      return { tag: tag, count: map[tag] };
    }).sort(function (a, b) { return b.count - a.count || a.tag.localeCompare(b.tag); });
  }

  function collectionCounts(items) {
    var map = { unfiled: 0 };
    (window.COLLECTIONS || []).forEach(function (c) { map[c.id] = 0; });
    items.forEach(function (item) {
      if (item.collection && map[item.collection] != null) map[item.collection]++;
      else map.unfiled++;
    });
    return map;
  }

  function groupByDate(items) {
    var groups = [];
    var index = {};
    items.forEach(function (item) {
      var date = new Date(item.saved);
      var start = new Date(date.getFullYear(), date.getMonth(), date.getDate());
      var diff = Math.floor((TODAY - start) / 86400000);
      var label;
      if (diff <= 0) label = "Today";
      else if (diff === 1) label = "Yesterday";
      else if (diff < 7) label = "This week";
      else if (date.getMonth() === TODAY.getMonth() && date.getFullYear() === TODAY.getFullYear()) {
        label = "Earlier in November";
      } else if (date.getMonth() === 9) label = "October";
      else if (date.getMonth() === 8) label = "September";
      else label = date.toLocaleDateString("en-US", { month: "long", year: "numeric" });
      if (!index[label]) {
        index[label] = { label: label, items: [] };
        groups.push(index[label]);
      }
      index[label].items.push(item);
    });
    return groups;
  }

  /* ------------------------------------------------------------------- shell */
  function initShell() {
    var sidebar = document.getElementById("sidebar");
    var toggle = document.getElementById("sidebarToggle");
    if (sidebar && toggle) {
      toggle.addEventListener("click", function () {
        sidebar.classList.toggle("collapsed");
        var collapsed = sidebar.classList.contains("collapsed");
        toggle.setAttribute("aria-expanded", String(!collapsed));
        var label = collapsed ? "Expand sidebar" : "Collapse sidebar";
        toggle.setAttribute("aria-label", label);
        toggle.setAttribute("title", label);
      });
      var narrow = window.matchMedia("(max-width: 900px)");
      var sync = function () {
        if (narrow.matches) sidebar.classList.add("collapsed");
      };
      sync();
      if (narrow.addEventListener) narrow.addEventListener("change", sync);
    }

    var ai = document.getElementById("aiToggle");
    if (ai) {
      ai.addEventListener("click", function () {
        var on = ai.getAttribute("aria-pressed") !== "true";
        ai.setAttribute("aria-pressed", String(on));
        ai.title = on ? "AI recommendations on" : "AI recommendations off";
        toast(on ? "AI mode on — searches would ask the engine" : "AI mode off");
      });
    }

    var form = document.getElementById("searchForm");
    if (form) {
      form.addEventListener("submit", function (event) {
        event.preventDefault();
        toast("Search is not wired in this prototype");
      });
    }

    document.querySelectorAll("[data-inert]").forEach(function (el) {
      el.addEventListener("click", function (event) {
        event.preventDefault();
        toast("“" + el.textContent.trim() + "” is shell chrome in this prototype");
      });
    });
  }

  /* --------------------------------------------------------------- listeners */
  function initCommon() {
    initShell();

    document.addEventListener("click", function (event) {
      var close = event.target.closest("[data-peek-close]");
      if (close) { closePeek(); return; }

      var removePeek = event.target.closest("[data-remove-peek]");
      if (removePeek) {
        var id = peekId;
        closePeek();
        if (id) removeFavorite(id);
        return;
      }

      var openDetail = event.target.closest("[data-open-detail]");
      if (openDetail) {
        toast("The form detail view is not part of this prototype");
        return;
      }

      var heart = event.target.closest("[data-heart]");
      if (heart) {
        event.preventDefault();
        event.stopPropagation();
        var card = heart.closest("[data-id]");
        if (card) removeFavorite(card.getAttribute("data-id"));
        return;
      }

      var select = event.target.closest("[data-select]");
      if (select) {
        event.preventDefault();
        event.stopPropagation();
        var selectCard = select.closest("[data-id]");
        if (selectCard) {
          document.dispatchEvent(new CustomEvent("favorites:toggle-select", {
            detail: { id: selectCard.getAttribute("data-id") }
          }));
        }
        return;
      }

      var peek = event.target.closest("[data-peek]");
      if (peek) {
        event.preventDefault();
        event.stopPropagation();
        var peekCard = peek.closest("[data-id]");
        if (peekCard) openPeek(peekCard.getAttribute("data-id"));
        return;
      }

      var card = event.target.closest("[data-fav-card]");
      if (card && !event.target.closest("button")) {
        if (document.body.classList.contains("selecting")) {
          document.dispatchEvent(new CustomEvent("favorites:toggle-select", {
            detail: { id: card.getAttribute("data-id") }
          }));
        } else {
          openPeek(card.getAttribute("data-id"));
        }
      }
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") closePeek();
      if ((event.key === "Enter" || event.key === " ") && event.target.matches("[data-fav-card]")) {
        event.preventDefault();
        var card = event.target;
        if (document.body.classList.contains("selecting")) {
          document.dispatchEvent(new CustomEvent("favorites:toggle-select", {
            detail: { id: card.getAttribute("data-id") }
          }));
        } else {
          openPeek(card.getAttribute("data-id"));
        }
      }
    });
  }

  document.addEventListener("DOMContentLoaded", initCommon);

  /* ----------------------------------------------------------------- exports */
  window.VG = {
    icon: icon,
    esc: esc,
    DOMAINS: DOMAINS,
    DOMAIN_ORDER: DOMAIN_ORDER,
    all: all,
    getItem: getItem,
    collectionById: collectionById,
    domainOf: domainOf,
    artClass: artClass,
    savedLabel: savedLabel,
    lastSavedLabel: lastSavedLabel,
    cardHTML: cardHTML,
    rowHTML: rowHTML,
    skeletonHTML: skeletonHTML,
    skeletonsHTML: skeletonsHTML,
    emptyHTML: emptyHTML,
    openPeek: openPeek,
    closePeek: closePeek,
    toast: toast,
    removeFavorite: removeFavorite,
    restoreFavorite: restoreFavorite,
    moveToCollection: moveToCollection,
    filterItems: filterItems,
    sortItems: sortItems,
    domainCounts: domainCounts,
    tagCounts: tagCounts,
    collectionCounts: collectionCounts,
    groupByDate: groupByDate
  };
})();
