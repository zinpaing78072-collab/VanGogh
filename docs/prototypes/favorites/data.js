/* Favorites prototype — sample library.
 *
 * Every record is a plausible app item: the artwork is the app's own poster
 * set (or the game/book/music artwork used by the earlier detail prototypes)
 * and the metadata follows the same fields the cards show today. Notes, tags,
 * collections and saved dates are invented for the prototype — they are the
 * "library" layer the real Favorites slice would add.
 */
window.FAVORITES = [
  /* ---------------------------------------------------------------- movies */
  {
    id: "m-inception", domain: "movie", title: "Inception", sub: "Christopher Nolan",
    year: 2010, meta: "Sci-Fi · Thriller", rating: 8.4,
    img: "assets/movies/inception.jpg", saved: "2026-11-25T09:20",
    collection: null, tags: ["big ideas", "rainy night"],
    note: "Still the cleanest heist movie ever cut."
  },
  {
    id: "m-interstellar", domain: "movie", title: "Interstellar", sub: "Christopher Nolan",
    year: 2014, meta: "Sci-Fi · Drama", rating: 8.4,
    img: "assets/movies/interstellar.jpg", saved: "2026-11-24T23:05",
    collection: null, tags: ["big feelings", "long haul"],
    note: "The docking scene on the biggest screen I could find."
  },
  {
    id: "m-dark-knight", domain: "movie", title: "The Dark Knight", sub: "Christopher Nolan",
    year: 2008, meta: "Crime · Action", rating: 9.0,
    img: "assets/movies/dark_knight.jpg", saved: "2026-11-22T20:40",
    collection: "neon-noir", tags: ["rainy night", "comfort"],
    note: "Ledger's Joker still sets the bar."
  },
  {
    id: "m-pulp-fiction", domain: "movie", title: "Pulp Fiction", sub: "Quentin Tarantino",
    year: 1994, meta: "Crime · Drama", rating: 8.9,
    img: "assets/movies/pulp_fiction.jpg", saved: "2026-11-19T22:15",
    collection: "neon-noir", tags: ["soundtrack", "beautiful mess"],
    note: "The needle drops do half the work."
  },
  {
    id: "m-godfather", domain: "movie", title: "The Godfather", sub: "Francis Ford Coppola",
    year: 1972, meta: "Crime · Drama", rating: 9.2,
    img: "assets/movies/godfather.jpg", saved: "2026-11-15T19:30",
    collection: null, tags: ["slow burn", "long haul"],
    note: "Watched it with the lights off. Correct choice."
  },
  {
    id: "m-forrest-gump", domain: "movie", title: "Forrest Gump", sub: "Robert Zemeckis",
    year: 1994, meta: "Drama · Romance", rating: 8.8,
    img: "assets/movies/forrest_gump.jpg", saved: "2026-11-08T21:00",
    collection: null, tags: ["comfort", "big feelings"],
    note: "Background comfort that sneaks up on you."
  },
  {
    id: "m-shawshank", domain: "movie", title: "The Shawshank Redemption", sub: "Frank Darabont",
    year: 1994, meta: "Drama", rating: 9.3,
    img: "assets/movies/shawshank.jpg", saved: "2026-10-30T20:10",
    collection: null, tags: ["comfort", "slow burn"],
    note: "The rain scene. Every time."
  },
  {
    id: "m-godzilla", domain: "movie", title: "Godzilla Minus One", sub: "Takashi Yamazaki",
    year: 2023, meta: "Sci-Fi · Action", rating: 7.7,
    img: "assets/movies/godzilla_minus_zero.jpg", saved: "2026-10-18T22:25",
    collection: null, tags: ["big feelings", "one sitting"],
    note: "A monster movie with a real human spine."
  },
  {
    id: "m-sunrise", domain: "movie", title: "Sunrise on the Reaping", sub: "Francis Lawrence",
    year: 2026, meta: "Sci-Fi · Adventure", rating: null,
    img: "assets/movies/hunger_games_sunrise.jpg", saved: "2026-10-30T18:00",
    collection: null, tags: ["worldbuilding"],
    note: "Bookmarking the release date."
  },
  {
    id: "m-resident-evil", domain: "movie", title: "Resident Evil", sub: "Paul W. S. Anderson",
    year: 2002, meta: "Horror · Action", rating: 6.7,
    img: "assets/movies/resident_evil.jpg", saved: "2026-09-27T23:40",
    collection: "neon-noir", tags: ["beautiful mess", "rainy night"],
    note: "Objectively a mess. Personally essential."
  },
  {
    id: "m-spider-man", domain: "movie", title: "Spider-Man: Brand New Day", sub: "Destin Daniel Cretton",
    year: 2026, meta: "Action · Adventure", rating: null,
    img: "assets/movies/spider_man_brand_new_day.jpg", saved: "2026-09-27T22:05",
    collection: null, tags: ["comfort"],
    note: "On the watch list the second it drops."
  },
  {
    id: "m-odyssey", domain: "movie", title: "The Odyssey", sub: "Christopher Nolan",
    year: 2026, meta: "Adventure · Drama", rating: null,
    img: "assets/movies/the_odyssey.jpg", saved: "2026-11-24T18:20",
    collection: null, tags: ["big ideas", "worldbuilding"],
    note: "The one I keep checking for a trailer."
  },

  /* ----------------------------------------------------------------- music */
  {
    id: "s-21", domain: "music", title: "21", sub: "Adele",
    year: 2011, meta: "Pop · Soul", rating: 7.7,
    img: "assets/music/21.jpg", saved: "2026-11-25T08:10",
    collection: null, tags: ["big feelings", "headphones"],
    note: "For the commute when the weather is doing too much."
  },
  {
    id: "s-abbey-road", domain: "music", title: "Abbey Road", sub: "The Beatles",
    year: 1969, meta: "Rock", rating: 9.2,
    img: "assets/music/abbey-road.jpg", saved: "2026-11-24T16:45",
    collection: "focus-loop", tags: ["comfort", "soundtrack"],
    note: "Side B is one continuous mood."
  },
  {
    id: "s-born-to-run", domain: "music", title: "Born to Run", sub: "Bruce Springsteen",
    year: 1975, meta: "Rock", rating: 8.7,
    img: "assets/music/born-to-run.jpg", saved: "2026-11-15T17:35",
    collection: null, tags: ["big feelings", "long haul"],
    note: "Windows down, even in November."
  },
  {
    id: "s-dark-side", domain: "music", title: "The Dark Side of the Moon", sub: "Pink Floyd",
    year: 1973, meta: "Progressive Rock", rating: 9.4,
    img: "assets/music/dark-side.jpg", saved: "2026-11-08T23:50",
    collection: "focus-loop", tags: ["headphones", "worldbuilding"],
    note: "Best with headphones and no phone."
  },
  {
    id: "s-kind-of-blue", domain: "music", title: "Kind of Blue", sub: "Miles Davis",
    year: 1959, meta: "Jazz", rating: 9.5,
    img: "assets/music/kind-of-blue.jpg", saved: "2026-10-30T19:15",
    collection: "focus-loop", tags: ["focus", "slow burn"],
    note: "The default focus loop. It never gets old."
  },
  {
    id: "s-rumours", domain: "music", title: "Rumours", sub: "Fleetwood Mac",
    year: 1977, meta: "Rock", rating: 9.0,
    img: "assets/music/rumours.jpg", saved: "2026-10-18T21:05",
    collection: null, tags: ["beautiful mess", "comfort"],
    note: "Every song is a breakup and a party."
  },
  {
    id: "s-thriller", domain: "music", title: "Thriller", sub: "Michael Jackson",
    year: 1982, meta: "Pop", rating: 9.1,
    img: "assets/music/thriller.jpg", saved: "2026-09-27T20:30",
    collection: "focus-loop", tags: ["soundtrack", "comfort"],
    note: "Still the best-produced pop record, argue with the wall."
  },

  /* ----------------------------------------------------------------- books */
  {
    id: "b-beloved", domain: "book", title: "Beloved", sub: "Toni Morrison",
    year: 1987, meta: "Toni Morrison · 324 pages", rating: 4.4,
    img: "assets/books/beloved.jpg", saved: "2026-11-25T07:40",
    collection: "late-night-reads", tags: ["big feelings", "slow burn"],
    note: "Read forty pages and had to sit in silence."
  },
  {
    id: "b-kindred", domain: "book", title: "Kindred", sub: "Octavia E. Butler",
    year: 1979, meta: "Octavia E. Butler · 287 pages", rating: 4.4,
    img: "assets/books/kindred.jpg", saved: "2026-11-24T22:30",
    collection: "late-night-reads", tags: ["one sitting", "worldbuilding"],
    note: "Time travel as a blade, not a toy."
  },
  {
    id: "b-left-hand", domain: "book", title: "The Left Hand of Darkness", sub: "Ursula K. Le Guin",
    year: 1969, meta: "Ursula K. Le Guin · 304 pages", rating: 4.3,
    img: "assets/books/left-hand-of-darkness.jpg", saved: "2026-11-22T23:15",
    collection: "late-night-reads", tags: ["worldbuilding", "slow burn"],
    note: "The ice crossing chapter is a whole novel by itself."
  },
  {
    id: "b-piranesi", domain: "book", title: "Piranesi", sub: "Susanna Clarke",
    year: 2020, meta: "Susanna Clarke · 245 pages", rating: 4.4,
    img: "assets/books/piranesi.jpg", saved: "2026-11-19T00:20",
    collection: "late-night-reads", tags: ["weird", "one sitting"],
    note: "Finished at 2 a.m. and immediately wanted to reread it."
  },
  {
    id: "b-hail-mary", domain: "book", title: "Project Hail Mary", sub: "Andy Weir",
    year: 2021, meta: "Andy Weir · 476 pages", rating: 4.5,
    img: "assets/books/project-hail-mary.jpg", saved: "2026-11-15T23:10",
    collection: null, tags: ["sci-fi", "one sitting"],
    note: "Rocky. That's it. That's the note."
  },
  {
    id: "b-remains", domain: "book", title: "The Remains of the Day", sub: "Kazuo Ishiguro",
    year: 1989, meta: "Kazuo Ishiguro · 258 pages", rating: 4.3,
    img: "assets/books/remains-of-the-day.jpg", saved: "2026-11-08T20:45",
    collection: null, tags: ["slow burn", "big feelings"],
    note: "A quiet book that lands like a truck."
  },
  {
    id: "b-achilles", domain: "book", title: "The Song of Achilles", sub: "Madeline Miller",
    year: 2011, meta: "Madeline Miller · 352 pages", rating: 4.4,
    img: "assets/books/song-of-achilles.jpg", saved: "2026-10-18T19:55",
    collection: null, tags: ["big feelings", "worldbuilding"],
    note: "You know how it ends. It still ruins you."
  },
  {
    id: "b-station-eleven", domain: "book", title: "Station Eleven", sub: "Emily St. John Mandel",
    year: 2014, meta: "Emily St. John Mandel · 333 pages", rating: 4.2,
    img: "assets/books/station-eleven.jpg", saved: "2026-09-27T22:50",
    collection: "late-night-reads", tags: ["comfort", "worldbuilding"],
    note: "Survival is insufficient. Taped it to my monitor."
  },

  /* ----------------------------------------------------------------- games */
  {
    id: "g-007", domain: "game", title: "007 First Light", sub: "IO Interactive",
    year: 2026, meta: "Action · Stealth", rating: null,
    img: "assets/games/007-first-light.jpg", saved: "2026-11-24T20:05",
    collection: null, tags: ["one sitting"],
    note: "Watching this one closely."
  },
  {
    id: "g-alan-wake-2", domain: "game", title: "Alan Wake 2", sub: "Remedy Entertainment",
    year: 2023, meta: "Survival Horror", rating: 9.0,
    img: "assets/games/alan-wake-2.jpg", saved: "2026-11-19T21:30",
    collection: null, tags: ["weird", "rainy night"],
    note: "The musical level is the best thing in games."
  },
  {
    id: "g-cyberpunk", domain: "game", title: "Cyberpunk 2077", sub: "CD Projekt Red",
    year: 2020, meta: "RPG", rating: 8.2,
    img: "assets/games/cyberpunk-2077.jpg", saved: "2026-11-15T22:00",
    collection: "neon-noir", tags: ["worldbuilding", "rainy night"],
    note: "Night City at 3 a.m. is a mood."
  },
  {
    id: "g-elden-ring", domain: "game", title: "Elden Ring", sub: "FromSoftware",
    year: 2022, meta: "Action RPG", rating: 9.6,
    img: "assets/games/elden-ring.jpg", saved: "2026-11-08T22:35",
    collection: "long-haul", tags: ["long haul", "worldbuilding"],
    note: "Two hundred hours and I still haven't finished."
  },
  {
    id: "g-forza", domain: "game", title: "Forza Horizon 6", sub: "Playground Games",
    year: 2026, meta: "Racing", rating: null,
    img: "assets/games/forza-horizon-6.jpg", saved: "2026-10-30T21:25",
    collection: "long-haul", tags: ["comfort", "soundtrack"],
    note: "Windows down, again."
  },
  {
    id: "g-ragnarok", domain: "game", title: "God of War Ragnarök", sub: "Santa Monica Studio",
    year: 2022, meta: "Action Adventure", rating: 9.4,
    img: "assets/games/god-of-war-ragnarok.jpg", saved: "2026-10-18T20:40",
    collection: null, tags: ["big feelings", "one sitting"],
    note: "The spear. That's all I'll say."
  },
  {
    id: "g-subnautica", domain: "game", title: "Subnautica", sub: "Unknown Worlds",
    year: 2018, meta: "Survival", rating: 8.6,
    img: "assets/games/subnautica.jpg", saved: "2026-09-27T21:15",
    collection: null, tags: ["weird", "one sitting"],
    note: "Thalassophobia, the game. Five stars."
  },
  {
    id: "g-yakuza-0", domain: "game", title: "Yakuza 0", sub: "Ryu Ga Gotoku Studio",
    year: 2015, meta: "Action Adventure", rating: 8.9,
    img: "assets/games/yakuza-0.jpg", saved: "2026-09-27T19:45",
    collection: "neon-noir", tags: ["beautiful mess", "soundtrack"],
    note: "Karaoke is canonically the main story."
  }
];

window.COLLECTIONS = [
  {
    id: "neon-noir", title: "Neon Noir", colour: "#FF2E97",
    blurb: "Rain, chrome and bad decisions."
  },
  {
    id: "late-night-reads", title: "Late-Night Reads", colour: "#A78BFA",
    blurb: "One more chapter, then sleep."
  },
  {
    id: "focus-loop", title: "Focus Loop", colour: "#00F0FF",
    blurb: "Albums that disappear an afternoon."
  },
  {
    id: "long-haul", title: "Long Haul", colour: "#C8FF4D",
    blurb: "Games that eat the weekend."
  }
];
