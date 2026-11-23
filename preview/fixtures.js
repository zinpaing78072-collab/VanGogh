/* Preview fixtures — the fake payloads the mock bridge serves.
 *
 * Loaded as a classic script BEFORE the module bundle, so
 * window.__previewFixtures is populated by the time the page starts asking for
 * content.
 *
 * Item shapes are the REAL resolved-item shapes the controllers hand the page
 * (see frontend/src/bridge/types.ts). The upcoming LLM engine picks *titles*
 * that the content sources resolve into these same objects, so these fixtures
 * stay valid across that slice — only the picking changes, not the card data.
 *
 * Deliberate coverage: long titles, a missing poster, a null rating, a
 * missing artist, a book with no page count. Those are the cases CSS gets
 * wrong first, so the preview must reproduce them.
 */
(function () {
    "use strict";

    var movies = [
        {
            id: "27205",
            title: "Inception",
            year: "2010",
            rating: 8.4,
            genre: "Science Fiction",
            overview: "A thief who steals corporate secrets through dream-sharing technology is given the inverse task of planting an idea into the mind of a C.E.O."
        },
        {
            id: "155",
            title: "The Dark Knight",
            year: "2008",
            rating: 8.5,
            genre: "Action",
            overview: "Batman raises the stakes in his war on crime when a rising criminal mastermind known as the Joker unleashes chaos on Gotham."
        },
        {
            id: "157336",
            title: "Interstellar",
            year: "2014",
            rating: 8.4,
            genre: "Adventure",
            overview: "A team of explorers travel through a wormhole in space in an attempt to ensure humanity's survival."
        },
        {
            id: "680",
            title: "Pulp Fiction",
            year: "1994",
            rating: 8.5,
            genre: "Thriller",
            overview: "The lives of two mob hitmen, a boxer, a gangster and his wife intertwine in four tales of violence and redemption."
        },
        {
            id: "238",
            title: "The Godfather",
            year: "1972",
            rating: 8.7,
            genre: "Crime",
            overview: "The aging patriarch of an organized crime dynasty transfers control of his clandestine empire to his reluctant son."
        },
        {
            // stress: no poster (placeholder path), no rating, no genre
            id: "278",
            title: "The Shawshank Redemption",
            year: "1994",
            rating: null,
            genre: "",
            overview: "Two imprisoned men bond over a number of years, finding solace and eventual redemption through acts of common decency."
        }
    ];

    var games = [
        {
            id: "10225",
            game_title: "Minecraft",
            year: "2011",
            rating: 9.2,
            genre: "Sandbox",
            overview: "Build, explore and survive in a procedurally generated world made of blocks."
        },
        {
            id: "9034",
            game_title: "The Legend of Zelda: Breath of the Wild",
            year: "2017",
            rating: 9.7,
            genre: "Action Adventure",
            overview: "Wake up in a ruined Hyrule and reclaim the kingdom from Calamity Ganon."
        },
        {
            id: "7720",
            game_title: "Hollow Knight",
            year: "2017",
            rating: 9.0,
            genre: "Metroidvania",
            overview: "Descend into Hallownest, a ruined insect kingdom, and uncover its secrets."
        },
        {
            // stress: very long single-token title, no rating
            id: "4411",
            game_title: "Supercalifragilisticexpialidociousdeluxeultimateedition",
            year: "2023",
            rating: null,
            genre: "Arcade",
            overview: "A deliberately unbreakable title, to prove the card truncates instead of overflowing."
        },
        {
            id: "5502",
            game_title: "Stardew Valley",
            year: "2016",
            rating: 8.9,
            genre: "Farming Sim",
            overview: "Inherit your grandfather's farm and build a new life in the valley."
        },
        {
            id: "3310",
            game_title: "Elden Ring",
            year: "2022",
            rating: 9.5,
            genre: "Action RPG",
            overview: "Rise, Tarnished, and be guided by grace to brandish the power of the Elden Ring."
        }
    ];

    var music = [
        {
            id: "c9f5b1a2-1111-4a3b-9c2d-7e6f5a4b3c2d",
            title: "Rumours",
            artist: "Fleetwood Mac",
            year: "1977",
            primaryType: "Album",
            genre: "Rock"
        },
        {
            id: "a1b2c3d4-2222-4e5f-8a9b-0c1d2e3f4a5b",
            title: "Random Access Memories",
            artist: "Daft Punk",
            year: "2013",
            primaryType: "Album",
            genre: "Electronic"
        },
        {
            // stress: no artist (Unknown Artist path), no genre
            id: "b2c3d4e5-3333-4f6a-9b0c-1d2e3f4a5b6c",
            title: "Untitled Demo Sessions",
            artist: "",
            year: "1998",
            primaryType: "EP",
            genre: ""
        },
        {
            id: "c3d4e5f6-4444-4a7b-8c1d-2e3f4a5b6c7d",
            title: "Kind of Blue",
            artist: "Miles Davis",
            year: "1959",
            primaryType: "Album",
            genre: "Jazz"
        },
        {
            id: "d4e5f6a7-5555-4b8c-9d2e-3f4a5b6c7d8e",
            title: "The Suburbs",
            artist: "Arcade Fire",
            year: "2010",
            primaryType: "Album",
            genre: "Indie Rock"
        },
        {
            id: "e5f6a7b8-6666-4c9d-8e3f-4a5b6c7d8e9f",
            title: "Blue Train",
            artist: "John Coltrane",
            year: "1958",
            primaryType: "Album",
            genre: "Jazz"
        }
    ];

    var books = [
        {
            workKey: "OL82563W",
            title: "The Lord of the Rings",
            author: "J. R. R. Tolkien",
            year: "1954",
            rating: 4.5,
            pages: 1216,
            editionCount: 312,
            subjects: "Fantasy, Adventure, Epic",
            firstSentence: "When Mr. Bilbo Baggins of Bag End announced that he would shortly be celebrating his eleventy-first birthday..."
        },
        {
            workKey: "OL1168007W",
            title: "Dune",
            author: "Frank Herbert",
            year: "1965",
            rating: 4.2,
            pages: 688,
            editionCount: 208,
            subjects: "Science Fiction, Politics, Ecology",
            firstSentence: "In the week before their departure to Arrakis, when all the final scurrying about had reached a nearly unbearable frenzy..."
        },
        {
            // stress: no page count, no first sentence, no rating
            workKey: "OL7890123W",
            title: "A Very Long Book Title That Will Not Fit On One Line No Matter How Wide The Card Is",
            author: "An Author With An Unusually Long Name",
            year: "2019",
            rating: null,
            pages: null,
            editionCount: null,
            subjects: null,
            firstSentence: null
        },
        {
            workKey: "OL27448W",
            title: "The Great Gatsby",
            author: "F. Scott Fitzgerald",
            year: "1925",
            rating: 3.9,
            pages: 180,
            editionCount: 640,
            subjects: "Classics, American Literature",
            firstSentence: "In my younger and more vulnerable years my father gave me some advice that I've been turning over in my mind ever since."
        },
        {
            workKey: "OL1513254W",
            title: "Neuromancer",
            author: "William Gibson",
            year: "1984",
            rating: 4.0,
            pages: 271,
            editionCount: 97,
            subjects: "Cyberpunk, Science Fiction",
            firstSentence: "The sky above the port was the color of television, tuned to a dead channel."
        },
        {
            workKey: "OL48029W",
            title: "One Hundred Years of Solitude",
            author: "Gabriel García Márquez",
            year: "1967",
            rating: 4.3,
            pages: 417,
            editionCount: 154,
            subjects: "Magical Realism, Latin American Literature",
            firstSentence: "Many years later, as he faced the firing squad, Colonel Aureliano Buendía was to remember that distant afternoon..."
        }
    ];

    window.__previewFixtures = {
        movie: movies,
        game: games,
        music: music,
        book: books,

        /* Song-detail dummy media: the real backend sends the same shape
         * (DummyMedia) — lyrics lines + a fixed length + no audio — so the
         * preview exercises the real detail view, not a lookalike. The rest is
         * real-shaped Genius detail data: credits, About prose, listen links,
         * palette and community stats. */
        songDetail: {
            durationSeconds: 252,
            audioUrl: "",
            lyrics: [
                { time: "0:00", text: "Demo lyrics — the lyrics source is not connected yet." },
                { time: "0:12", text: "Starlight on the water, city lights below," },
                { time: "0:20", text: "we were only passing through the afterglow." },
                { time: "0:28", text: "Every quiet corner keeps a little flame," },
                { time: "0:36", text: "every open window calls me by my name." },
                { time: "0:46", text: "Hold the night a little longer," },
                { time: "0:54", text: "let the morning wait its turn." },
                { time: "1:02", text: "Demo lyrics — end of placeholder." }
            ],
            writers: ["Preview Writer", "Preview Composer"],
            producers: ["Preview Producer"],
            language: "English",
            translationCount: 22,
            credits: [
                { label: "Mixing Engineer", names: ["Preview Mixer"] },
                { label: "Mastering Engineer", names: ["Preview Masterer"] },
                { label: "Label", names: ["Preview Records", "Preview Music Group"] }
            ],
            media: [
                { provider: "YouTube", type: "video", url: "https://www.youtube.com/watch?v=preview", startSeconds: 0 },
                { provider: "SoundCloud", type: "audio", url: "https://soundcloud.com/preview", startSeconds: 0 }
            ],
            about: "First paragraph of Genius editorial copy, long enough to need the collapsed state. "
                + "It keeps going so the clamp has something to hide behind the fade.\n\n"
                + "Second paragraph carries the release story.\n\n"
                + "Third paragraph carries the chart story.",
            recordingLocation: "Electric Lady Studios, New York, NY",
            stats: { contributors: 224, transcribers: 4, annotationCount: 17, pyongsCount: 81 }
        },

        /* Movie-detail extras the card fixtures do not carry. The mock composes
         * the real MovieDetail shape from these plus the clicked movie item, so
         * the preview exercises the actual bento view — facts ledger, cast,
         * stills, similar rail — not a lookalike. Artwork is generated per
         * slot, like the controller's data-URI stream. */
        movieDetail: {
            tagline: "Your mind is the scene of the crime.",
            runtimeMinutes: 148,
            certification: "PG-13",
            keywords: ["dream", "heist", "subconscious", "architecture", "time", "memory"],
            facts: [
                { label: "Release", value: "Jul 16, 2010", muted: false },
                { label: "Runtime", value: "2h 28m", muted: false },
                { label: "Director", value: "Christopher Nolan", muted: false },
                { label: "Screenplay", value: "Christopher Nolan", muted: true },
                { label: "Cinematography", value: "Wally Pfister", muted: true },
                { label: "Music", value: "Hans Zimmer", muted: true },
                { label: "Studio", value: "Warner Bros., Legendary, Syncopy", muted: true },
                { label: "Country", value: "US, UK", muted: true },
                { label: "Budget", value: "$160M", muted: false },
                { label: "Box office", value: "$836.8M", muted: false },
                { label: "Certification", value: "PG-13", muted: false },
                { label: "Status", value: "Released", muted: false }
            ],
            cast: [
                { name: "Leonardo DiCaprio", role: "Dom Cobb" },
                { name: "Joseph Gordon-Levitt", role: "Arthur" },
                { name: "Elliot Page", role: "Ariadne" },
                { name: "Tom Hardy", role: "Eames" },
                { name: "Ken Watanabe", role: "Saito" },
                { name: "Marion Cotillard", role: "Mal" },
                { name: "Christopher Nolan", role: "Director", crew: true },
                { name: "Wally Pfister", role: "Cinematography", crew: true }
            ],
            stills: [
                { caption: "The city folds" },
                { caption: "Limbo" },
                { caption: "Snow fortress" }
            ]
        },

        /* Game-detail extras the card fixtures do not carry. The mock composes
         * the real GameDetail shape from these plus the clicked game item, so
         * the preview exercises the actual immersive view — record rail, stat
         * band, article, stills, related rail — not a lookalike. The rating is
         * TheGamesDB's string, never a score; artwork is generated per slot,
         * like the controller's data-URI stream. A game with no entry here gets
         * the card's own year/genre and no optional fields, which is the
         * sparse-record path the page must also survive. */
        gameDetail: {
            "10225": {
                releaseDate: "2011-11-18",
                rating: "E10+ - Everyone 10+",
                players: 4,
                genres: ["Sandbox", "Adventure"],
                platforms: ["Nintendo Switch", "PlayStation 4", "Xbox One", "PC"],
                publishers: ["Mojang Studios"],
                developers: ["Mojang Studios"],
                overview: "Build, explore and survive in a procedurally generated world made of blocks. Every seed is a different world, from mushroom islands to deep caves, and every block can be moved.\n\nSurvival mode adds hunger, crafting and nights that belong to the monsters; creative mode takes the monsters away and leaves the whole world as material. The record's overview stops there — no price, playtime or score is attached to it.",
                youtube: "https://www.youtube.com/watch?v=preview",
                hero: true,
                boxart: true,
                stills: 3,
                relatedSource: "series",
                related: [
                    { id: "10226", title: "Minecraft Dungeons", year: "2020", rating: "E10+ - Everyone 10+", genre: "Action RPG" },
                    { id: "10227", title: "Minecraft Legends", year: "2023", rating: "E10+ - Everyone 10+", genre: "Action Strategy" },
                    { id: "10228", title: "Minecraft: Story Mode", year: "2015", rating: "E10+ - Everyone 10+", genre: "Adventure" },
                    { id: "10229", title: "Minecraft Earth", year: "2019", rating: "E10+ - Everyone 10+", genre: "Sandbox" }
                ]
            },
            "9034": {
                releaseDate: "2017-03-03",
                rating: "E10+ - Everyone 10+",
                players: 1,
                genres: ["Action", "Adventure"],
                platforms: ["Nintendo Switch", "Wii U"],
                publishers: ["Nintendo"],
                developers: ["Nintendo EPD"],
                overview: "Wake up in a ruined Hyrule and reclaim the kingdom from Calamity Ganon. The plateau teaches you to climb, cook and listen to the wind, and then the map opens all at once.",
                youtube: "",
                hero: true,
                boxart: true,
                stills: 2,
                relatedSource: "catalogue",
                related: [
                    { id: "9035", title: "The Legend of Zelda: Tears of the Kingdom", year: "2023", rating: "E10+ - Everyone 10+", genre: "Action Adventure" },
                    { id: "7720", title: "Hollow Knight", year: "2017", rating: "E10+ - Everyone 10+", genre: "Metroidvania" },
                    { id: "3310", title: "Elden Ring", year: "2022", rating: "M - Mature 17+", genre: "Action RPG" }
                ]
            },
            "7720": {
                releaseDate: "2017-02-24",
                rating: "E10+ - Everyone 10+",
                players: 1,
                genres: ["Platform", "Adventure"],
                platforms: ["PC", "Nintendo Switch", "PlayStation 4"],
                publishers: ["Team Cherry"],
                developers: ["Team Cherry"],
                overview: "Descend into Hallownest, a ruined insect kingdom, and uncover its secrets. The map is drawn by hand, one bench at a time, and the quiet is part of the design.",
                youtube: "https://www.youtube.com/watch?v=preview",
                hero: true,
                boxart: true,
                stills: 1,
                relatedSource: "catalogue",
                related: [
                    { id: "7721", title: "Hollow Knight: Silksong", year: "2024", rating: "E10+ - Everyone 10+", genre: "Metroidvania" },
                    { id: "5502", title: "Stardew Valley", year: "2016", rating: "E10+ - Everyone 10+", genre: "Farming Sim" }
                ]
            },
            // The stress game: an unbroken title, no rating, no publisher or
            // developer, no hero and a single screenshot — every optional field
            // is absent, so every placeholder path is visible at once.
            "4411": {
                releaseDate: "",
                rating: "",
                players: 0,
                genres: ["Arcade"],
                platforms: [],
                publishers: [],
                developers: [],
                overview: "A deliberately unbreakable title, to prove the card truncates instead of overflowing. This record carries no rating, no platform and no publisher, so the page shows only what it has.",
                youtube: "",
                hero: false,
                boxart: false,
                stills: 1,
                relatedSource: "catalogue",
                related: [
                    { id: "10225", title: "Minecraft", year: "2011", rating: "E10+ - Everyone 10+", genre: "Sandbox" },
                    { id: "5502", title: "Stardew Valley", year: "2016", rating: "E10+ - Everyone 10+", genre: "Farming Sim" }
                ]
            }
        },

        /* Book-detail extras the card fixtures do not carry. The mock composes
         * the real BookDetail shape from these plus the clicked book item, so
         * the preview exercises the actual bento view — record ledger, story,
         * editions, author card, similar rail — not a lookalike. Artwork is
         * generated per slot, like the controller's data-URI stream. */
        bookDetail: {
            descriptions: {
                "OL82563W": "In a quiet corner of Middle-earth, a hobbit is swept into a war of rings, wizards and old songs. The road runs from the Shire to Mount Doom, and every step asks what a small person is willing to carry.",
                "OL1168007W": "On the desert planet Arrakis, water is wealth and spice is power. A young heir learns the ways of the Fremen while empires move against his family, and the desert itself becomes the only thing worth trusting.",
                "OL7890123W": "A deliberately long preview description, long enough to wrap past the tile's first lines and prove the story column reflows without pushing the grid sideways.",
                "OL27448W": "A summer on Long Island, a green light across the bay, and a man who believes the past can be bought back. Fitzgerald's short, bright novel keeps its distance from the party it describes.",
                "OL1513254W": "Case is a burned-out console cowboy with one last job. The sky above the port is the color of television, tuned to a dead channel, and the future is a network of glass and debt.",
                "OL48029W": "Macondo begins with a family and a prophecy and ends with a wind that erases the town. García Márquez writes generations in circles, each one carrying the same names and the same solitude."
            },
            authorBios: {
                "J. R. R. Tolkien": "J. R. R. Tolkien was an English writer, poet and philologist best known for The Hobbit and The Lord of the Rings. He invented languages before he invented the stories that would use them.",
                "Frank Herbert": "Frank Herbert was an American science-fiction writer whose Dune cycle turned ecology, religion and imperial politics into one of the genre's defining sagas.",
                "F. Scott Fitzgerald": "F. Scott Fitzgerald was an American novelist and short-story writer, the voice of the Jazz Age and its hangover. The Great Gatsby is his most read and most taught book.",
                "William Gibson": "William Gibson is an American-Canadian writer who named cyberspace and then spent a career complicating what the word could mean.",
                "Gabriel García Márquez": "Gabriel García Márquez was a Colombian novelist and Nobel laureate, the best-known practitioner of magical realism in Latin American letters."
            },
            authorDates: {
                "J. R. R. Tolkien": "1892 \u2013 1973",
                "Frank Herbert": "1920 \u2013 1986",
                "F. Scott Fitzgerald": "1896 \u2013 1940",
                "William Gibson": "born 1948",
                "Gabriel García Márquez": "1927 \u2013 2014"
            },
            settings: {
                "OL82563W": "Middle-earth",
                "OL1168007W": "Arrakis",
                "OL1513254W": "Chiba City",
                "OL48029W": "Macondo"
            },
            ratingCount: 164
        },

        /* The plural + label the app shows in the section title, so the mock can
         * honour the same wording the real controllers produce. */
        labels: {
            movie: { plural: "Movies", empty: "movies" },
            game: { plural: "Games", empty: "games" },
            music: { plural: "Music", empty: "songs" },
            book: { plural: "Books", empty: "books" }
        }
    };
})();
