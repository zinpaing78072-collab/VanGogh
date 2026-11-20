// Shared catalog for the Van Gogh games demo.
// Every card on index.html and every detail page in gamedetail.html reads from this list.
window.GAMES = [
  {
    slug: "roblox",
    title: "Roblox",
    cover: "images/roblox-cover.jpg",
    year: 2006,
    rating: 4.6,
    genres: ["Sandbox", "Multiplayer", "UGC", "Adventure"],
    developer: "Roblox Corporation",
    publisher: "Roblox Corporation",
    release: "1 Sep 2006",
    overview:
      "Roblox is a platform where millions of people build, play and share experiences together. Jump between obby courses, roleplay towns, tycoons and shooters — every world here is made by the community, and a brand new one is always waiting.",
    more:
      "Everything runs on Roblox Studio and the Roblox engine, so the same world plays on PC, console and mobile — and your avatar, friends and inventory follow you across every experience.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "GAME OF THE YEAR",
    meta: "100+ worlds",
    goty: true,
    screenshots: [
      { src: "images/roblox-shot1.jpg", caption: "Adopt Me!" },
      { src: "images/roblox-shot2.jpg", caption: "Brookhaven RP" },
      { src: "images/roblox-shot3.jpg", caption: "Blox Fruits" },
      { src: "images/roblox-shot4.jpg", caption: "Grow a Garden" }
    ],
    shotsSub: "12 shots · Community worlds",
    ratingsCount: 2400000,
    ratingBars: [
      { pct: 93, count: "1.6M" },
      { pct: 27, count: "520K" },
      { pct: 11, count: "180K" },
      { pct: 6, count: "64K" },
      { pct: 5, count: "36K" }
    ],
    trailer: { image: "images/roblox-trailer.jpg", seconds: 154, views: "24M" },
    featuresSub: "Everything you can play, build and share",
    features: [
      {
        icon: "i-swords",
        title: "Millions of experiences",
        body: "Jump between obbies, tycoons, shooters and hangouts — thousands of new worlds launch every day."
      },
      {
        icon: "i-map",
        title: "Build with Roblox Studio",
        body: "Create and publish your own game with free tools, then earn Robux from players."
      },
      {
        icon: "i-users",
        title: "Play with friends anywhere",
        body: "Cross-platform play on PC, console and mobile, with parties and voice chat built in."
      },
      {
        icon: "i-hourglass",
        title: "Endless avatar style",
        body: "Dress up with millions of community-made items, limited drops and brand collabs."
      }
    ],
    requirements: {
      badge: "60 FPS · High",
      min: {
        os: "Windows 10 / macOS 12",
        cpu: "Dual-core 1.6 GHz or faster",
        ram: "4 GB RAM",
        gpu: "DirectX 10 capable GPU",
        storage: "1 GB free space",
        dx: "Version 10"
      },
      rec: {
        os: "Windows 11 / macOS 14",
        cpu: "Quad-core 2.4 GHz or faster",
        ram: "8 GB RAM",
        gpu: "DirectX 11 capable GPU",
        storage: "2 GB free space",
        dx: "Version 11"
      }
    },
    reviews: [
      {
        initials: "MI",
        name: "Mira Chen",
        role: "Played 400+ hours",
        stars: 5,
        title: "My kids and I build together every weekend",
        body:
          "One evening it is an obby, the next a café roleplay with friends. Cross-play means the console in the living room and the phone in the car are the same world. Nothing else in gaming works quite like this.",
        helpful: "128"
      },
      {
        initials: "DE",
        name: "Dev Patel",
        role: "Played 120 hours",
        stars: 4,
        title: "Endless variety — quality varies",
        body:
          "You can find a masterpiece or a game that breaks in five minutes. Discovery is still the weak point, but once you land in a good community the hours disappear.",
        helpful: "94"
      }
    ]
  },
  {
    slug: "pubg",
    title: "PUBG: BATTLEGROUNDS",
    cover: "images/pubg.jpg",
    year: 2017,
    rating: 4.3,
    genres: ["Battle Royale", "Shooter"],
    developer: "Krafton",
    publisher: "Krafton",
    release: "23 Mar 2017",
    overview:
      "Drop onto an island with 99 other players, scavenge weapons and supplies, and be the last squad standing as the play zone closes in.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S"],
    eyebrow: "BATTLE ROYALE",
    meta: "100-player matches"
  },
  {
    slug: "stumble-guys",
    title: "Stumble Guys",
    cover: "images/stumbleguys.jpg",
    year: 2021,
    rating: 4.5,
    genres: ["Party", "Battle Royale"],
    developer: "Scopely",
    publisher: "Scopely",
    release: "12 Feb 2021",
    overview:
      "Race up to 32 players through obstacle courses, dodge the spinning hammers and qualify round after round until one stumble decides it all.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "PARTY ROYALE",
    meta: "32-player races"
  },
  {
    slug: "among-us",
    title: "Among Us",
    cover: "images/amongus.jpg",
    year: 2018,
    rating: 4.4,
    genres: ["Social Deduction", "Party"],
    developer: "InnerSloth",
    publisher: "InnerSloth",
    release: "15 Jun 2018",
    overview:
      "Crewmates race to finish tasks while impostors sabotage, vent and pick off the crew one by one. Trust no one in this social deduction classic.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "SOCIAL DEDUCTION",
    meta: "4–15 players"
  },
  {
    slug: "fall-guys",
    title: "Fall Guys",
    cover: "images/fallguys.jpg",
    year: 2020,
    rating: 4.2,
    genres: ["Party", "Platformer"],
    developer: "Mediatonic",
    publisher: "Epic Games",
    release: "4 Aug 2020",
    overview:
      "Sixty bean-shaped contenders tumble through absurd obstacle courses and team games until a single winner takes the crown.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "PARTY PLATFORMER",
    meta: "60-player knockout"
  },
  {
    slug: "rocket-league",
    title: "Rocket League",
    cover: "images/rocketleague.jpg",
    year: 2015,
    rating: 4.7,
    genres: ["Sports", "Racing"],
    developer: "Psyonix",
    publisher: "Psyonix",
    release: "7 Jul 2015",
    overview:
      "Rocket-powered cars meet football in a high-speed contest of aerials, saves and last-second goals.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "FOOTBALL, BUT CARS",
    meta: "Online multiplayer"
  },
  {
    slug: "gta-v",
    title: "Grand Theft Auto V",
    cover: "images/gtav.jpg",
    year: 2013,
    rating: 4.8,
    genres: ["Action", "Open World"],
    developer: "Rockstar North",
    publisher: "Rockstar Games",
    release: "17 Sep 2013",
    overview:
      "Three criminals are pulled into one another's lives across Los Santos — heists, chases and a satirical open world that still feels alive.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S"],
    eyebrow: "OPEN WORLD",
    meta: "Story + Online"
  },
  {
    slug: "terraria",
    title: "Terraria",
    cover: "images/terraria.jpg",
    year: 2011,
    rating: 4.7,
    genres: ["Sandbox", "Adventure"],
    developer: "Re-Logic",
    publisher: "Re-Logic",
    release: "16 May 2011",
    overview:
      "Dig, build and fight through a procedurally generated world of ore, bosses and secrets in this side-scrolling sandbox.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "SANDBOX ADVENTURE",
    meta: "Single-player & co-op"
  },
  {
    slug: "apex-legends",
    title: "Apex Legends",
    cover: "images/apex.jpg",
    year: 2019,
    rating: 4.4,
    genres: ["Battle Royale", "Shooter"],
    developer: "Respawn Entertainment",
    publisher: "Electronic Arts",
    release: "4 Feb 2019",
    overview:
      "Squad-based battle royale with hero abilities, fast movement and a ping system that makes team play work without a word.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "BATTLE ROYALE",
    meta: "Squad battle royale"
  },
  {
    slug: "counter-strike-2",
    title: "Counter-Strike 2",
    cover: "images/cs2.jpg",
    year: 2023,
    rating: 4.5,
    genres: ["Shooter", "Tactical"],
    developer: "Valve",
    publisher: "Valve",
    release: "27 Sep 2023",
    overview:
      "Round-based tactical shooter where economy, utility and precision decide every site take.",
    platforms: ["PC"],
    eyebrow: "TACTICAL SHOOTER",
    meta: "5v5 tactical"
  },
  {
    slug: "call-of-duty",
    title: "Call of Duty",
    cover: "images/cod.jpg",
    year: 2003,
    rating: 4.1,
    genres: ["Shooter", "Multiplayer"],
    developer: "Infinity Ward",
    publisher: "Activision",
    release: "29 Oct 2003",
    overview:
      "The series that defined cinematic military shooters — short campaigns, huge multiplayer and an endless grind of unlocks.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S"],
    eyebrow: "MILITARY SHOOTER",
    meta: "Campaign + multiplayer"
  },
  {
    slug: "ea-sports-fc-25",
    title: "EA SPORTS FC 25",
    cover: "images/eafc.jpg",
    year: 2024,
    rating: 3.9,
    genres: ["Sports", "Football"],
    developer: "EA Vancouver",
    publisher: "EA Sports",
    release: "27 Sep 2024",
    overview:
      "Club football with updated tactics, Ultimate Team and a full suite of modes for solo and online play.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "CLUB FOOTBALL",
    meta: "Ultimate Team"
  },
  {
    slug: "rainbow-six-siege",
    title: "Rainbow Six Siege",
    cover: "images/r6.jpg",
    year: 2015,
    rating: 4.2,
    genres: ["Tactical", "Shooter"],
    developer: "Ubisoft Montreal",
    publisher: "Ubisoft",
    release: "1 Dec 2015",
    overview:
      "Attackers and defenders trade intel and gadgets through destructible walls in tense, round-based tactical combat.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S"],
    eyebrow: "TACTICAL SHOOTER",
    meta: "5v5 tactical"
  },
  {
    slug: "rust",
    title: "Rust",
    cover: "images/rust.jpg",
    year: 2018,
    rating: 4.0,
    genres: ["Survival", "Multiplayer"],
    developer: "Facepunch Studios",
    publisher: "Facepunch Studios",
    release: "8 Feb 2018",
    overview:
      "Wake up naked on a beach, gather, build and fight for survival on a server where nothing stays yours for long.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S"],
    eyebrow: "SURVIVAL",
    meta: "Online survival"
  },
  {
    slug: "valheim",
    title: "Valheim",
    cover: "images/valheim.jpg",
    year: 2021,
    rating: 4.8,
    genres: ["Survival", "Co-op"],
    developer: "Iron Gate Studio",
    publisher: "Coffee Stain Publishing",
    release: "2 Feb 2021",
    overview:
      "Norse survival in a procedurally generated purgatory — build longhouses, sail to new biomes and take on the Forsaken.",
    platforms: ["PC", "Xbox Series X|S"],
    eyebrow: "NORSE SURVIVAL",
    meta: "Co-op survival"
  },
  {
    slug: "it-takes-two",
    title: "It Takes Two",
    cover: "images/ittakestwo.jpg",
    year: 2021,
    rating: 4.9,
    genres: ["Co-op", "Adventure"],
    developer: "Hazelight Studios",
    publisher: "Electronic Arts",
    release: "26 Mar 2021",
    overview:
      "A co-op only adventure where a couple turned into dolls works together through inventive, constantly changing levels.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "CO-OP ADVENTURE",
    meta: "2-player co-op"
  },
  {
    slug: "elden-ring",
    title: "Elden Ring",
    cover: "images/eldenring.jpg",
    year: 2022,
    rating: 4.9,
    genres: ["Action RPG", "Open World"],
    developer: "FromSoftware",
    publisher: "Bandai Namco",
    release: "25 Feb 2022",
    overview:
      "An open-world soulslike from FromSoftware — explore the Lands Between, die often and grow strong enough to become Elden Lord.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S"],
    eyebrow: "ACTION RPG",
    meta: "Single-player"
  },
  {
    slug: "cyberpunk-2077",
    title: "Cyberpunk 2077",
    cover: "images/cyberpunk.jpg",
    year: 2020,
    rating: 4.2,
    genres: ["Action RPG", "Open World"],
    developer: "CD Projekt Red",
    publisher: "CD Projekt",
    release: "10 Dec 2020",
    overview:
      "A neon open-world RPG set in Night City, where cyberware, choices and Keanu Reeves shape the merc you become.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S"],
    eyebrow: "OPEN-WORLD RPG",
    meta: "Single-player RPG"
  },
  {
    slug: "hogwarts-legacy",
    title: "Hogwarts Legacy",
    cover: "images/hogwarts.jpg",
    year: 2023,
    rating: 4.6,
    genres: ["Action RPG", "Open World"],
    developer: "Avalanche Software",
    publisher: "Warner Bros. Games",
    release: "10 Feb 2023",
    overview:
      "An open-world wizarding adventure set in the 1800s — attend classes, explore the Highlands and uncover an ancient secret.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "WIZARDING WORLD",
    meta: "Single-player"
  },
  {
    slug: "gang-beasts",
    title: "Gang Beasts",
    cover: "images/gang-beasts.jpg",
    year: 2017,
    rating: 4.3,
    genres: ["Party", "Fighting"],
    developer: "Boneloaf",
    publisher: "Double Fine Presents",
    release: "12 Dec 2017",
    overview:
      "Wobbly, physics-driven brawling where ragdoll fighters fling each other off industrial scaffolding.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "PARTY BRAWLER",
    meta: "Local & online"
  },
  {
    slug: "garrys-mod",
    title: "Garry's Mod",
    cover: "images/garrys-mod.jpg",
    year: 2006,
    rating: 4.5,
    genres: ["Sandbox", "Physics"],
    developer: "Facepunch Studios",
    publisher: "Facepunch Studios",
    release: "29 Nov 2006",
    overview:
      "A sandbox built on Source where players spawn props, weld contraptions and invent game modes that barely obey physics.",
    platforms: ["PC"],
    eyebrow: "SANDBOX",
    meta: "Sandbox multiplayer"
  },
  {
    slug: "gorilla-tag",
    title: "Gorilla Tag",
    cover: "images/gorilla-tag.jpg",
    year: 2021,
    rating: 4.4,
    genres: ["VR", "Party"],
    developer: "Another Axiom",
    publisher: "Another Axiom",
    release: "1 Jan 2021",
    overview:
      "A VR playground where you swing through the trees with your hands and chase friends in a game of tag.",
    platforms: ["PC", "VR"],
    eyebrow: "VR PLAYGROUND",
    meta: "VR multiplayer"
  },
  {
    slug: "overcooked-2",
    title: "Overcooked! 2",
    cover: "images/overcooked2.jpg",
    year: 2018,
    rating: 4.6,
    genres: ["Co-op", "Party"],
    developer: "Ghost Town Games",
    publisher: "Team17",
    release: "7 Aug 2018",
    overview:
      "Co-op cooking chaos on moving kitchens, where communication matters as much as chopping.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "CO-OP CHAOS",
    meta: "2–4 player co-op"
  },
  {
    slug: "rec-room",
    title: "Rec Room",
    cover: "images/rec-room.jpg",
    year: 2016,
    rating: 4.2,
    genres: ["VR", "Social"],
    developer: "Rec Room Inc.",
    publisher: "Rec Room Inc.",
    release: "1 Jun 2016",
    overview:
      "A social VR space with user-made rooms, quests and a creative toolkit for building your own games.",
    platforms: ["PC", "VR", "PlayStation 5", "Xbox Series X|S"],
    eyebrow: "SOCIAL VR",
    meta: "VR social"
  },
  {
    slug: "crab-game",
    title: "Crab Game",
    cover: "images/crab-game.jpg",
    year: 2021,
    rating: 4.1,
    genres: ["Party", "Survival"],
    developer: "Dani",
    publisher: "Dani",
    release: "29 Oct 2021",
    overview:
      "A deadly playground of mini-games inspired by squid games — last crab standing wins.",
    platforms: ["PC"],
    eyebrow: "PARTY SURVIVAL",
    meta: "Party multiplayer"
  },
  {
    slug: "brawlhalla",
    title: "Brawlhalla",
    cover: "images/hdr-brawlhalla.jpg",
    year: 2017,
    rating: 4.4,
    genres: ["Fighting", "Party"],
    developer: "Blue Mammoth Games",
    publisher: "Ubisoft",
    release: "17 Oct 2017",
    overview:
      "Free-to-play platform fighter with a roster of legends, fast aerial combat and ranked brawls.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "PLATFORM FIGHTER",
    meta: "Free-to-play"
  },
  {
    slug: "golf-it",
    title: "Golf It!",
    cover: "images/hdr-golf-it.jpg",
    year: 2017,
    rating: 4.3,
    genres: ["Sports", "Party"],
    developer: "Perfuse Entertainment",
    publisher: "Perfuse Entertainment",
    release: "26 Jan 2017",
    overview:
      "A physics golf party game with level editors, wild obstacles and friends who will absolutely blame lag.",
    platforms: ["PC"],
    eyebrow: "PARTY GOLF",
    meta: "Party golf"
  },
  {
    slug: "human-fall-flat",
    title: "Human Fall Flat",
    cover: "images/hdr-human-fall-flat.jpg",
    year: 2016,
    rating: 4.5,
    genres: ["Puzzle", "Co-op"],
    developer: "No Brakes Games",
    publisher: "Curve Games",
    release: "22 Jul 2016",
    overview:
      "A floppy physics puzzler where two wobbly arms are all you need to solve dreamlike levels with friends.",
    platforms: ["PC", "PlayStation 5", "Xbox Series X|S", "Switch"],
    eyebrow: "PHYSICS PUZZLER",
    meta: "Co-op puzzle"
  },
  {
    slug: "pummel-party",
    title: "Pummel Party",
    cover: "images/hdr-pummel-party.jpg",
    year: 2018,
    rating: 4.4,
    genres: ["Party", "Board"],
    developer: "Rebuilt Games",
    publisher: "Rebuilt Games",
    release: "20 Sep 2018",
    overview:
      "A board-game party brawler with dice rolls, minigames and generous amounts of backstabbing.",
    platforms: ["PC"],
    eyebrow: "PARTY BOARD",
    meta: "4–8 players"
  }
];

window.GAMES_BY_SLUG = Object.fromEntries(window.GAMES.map((game) => [game.slug, game]));
