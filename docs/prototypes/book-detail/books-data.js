/*
 * Sample data for the Book detail prototypes — REAL Open Library rows.
 *
 * Source: https://openlibrary.org/trending/weekly.json?limit=20&fields=
 *   key,title,author_name,first_publish_year,cover_i,first_sentence,subject,
 *   edition_count,number_of_pages_median,ratings_average
 * Pulled 2026-11-17. Six rows were kept verbatim, except:
 *   - `subject` arrays are truncated to the first 10 entries (the app only
 *     renders the first 3; the full crowdsourced arrays run 50–100+ items).
 *   - `first_sentence` arrays are truncated to the first ~6 entries.
 * Everything else — keys, ratings, page counts, edition counts — is exactly
 * what the API returned.
 *
 * `toVanGoghBook()` below mirrors the field extraction in
 * vangogh/book/OpenLibraryBookService.java (lenient defaults, first-3
 * subjects joined with " · ", English-first-sentence picking, cover URL
 * build) so every prototype renders the app's actual Book record shape:
 *   { workKey, title, author, year, rating, subjects, firstSentence,
 *     pages, editionCount, coverUrl }
 */

window.VANGOGH_BOOK_ROWS = [
  {
    "key": "/works/OL1168083W",
    "title": "Nineteen Eighty-Four",
    "author_name": ["George Orwell"],
    "first_publish_year": 1949,
    "cover_i": 9267242,
    "edition_count": 727,
    "number_of_pages_median": 318,
    "ratings_average": 4.289183,
    "first_sentence": [
      "Era un dia d\u2019abril fred i llumin\u00f3s i els rellotges estaven tocant les tretze.",
      "IT was a bright cold day in April, and the clocks were striking thirteen.",
      "It was a bright cold day in April, and the clocks were striking thriteen.",
      "Era un d\u00eda fr\u00edo y luminoso de abril y los relojes estaban dando las trece.",
      "Hari yang dingin dan cerah di bulan April, dan jam-jam berdentang tiga belas kali.",
      "It was a bright cold day in April, and the clocks were striking thirteen."
    ],
    "subject": [
      "futurology",
      "censorship",
      "surveillance",
      "rebels",
      "sting operations",
      "historical negationism",
      "memory holes",
      "thoughtcrime",
      "Outer Party",
      "resistance movements"
    ]
  },
  {
    "key": "/works/OL66554W",
    "title": "Pride and Prejudice",
    "author_name": ["Jane Austen"],
    "first_publish_year": 1813,
    "cover_i": 14348537,
    "edition_count": 4042,
    "number_of_pages_median": 351,
    "ratings_average": 4.200957,
    "first_sentence": [
      "IT IS A TRUTH universally acknowledged that a single man in possession of a good fortune must be in want of a wife.",
      "IT is a truth universally acknowledge, that a single man in possession of a good fortune, must be in want of a wife.",
      "IT is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife."
    ],
    "subject": [
      "Fiction, Romance, Historical, Regency",
      "British and Irish fiction (fictional works by one author)",
      "Brothers and sisters",
      "Courtship",
      "Drama",
      "English fiction",
      "English literature",
      "Families",
      "Family",
      "Family life"
    ]
  },
  {
    "key": "/works/OL5735363W",
    "title": "The Hunger Games",
    "author_name": ["Suzanne Collins"],
    "first_publish_year": 2008,
    "cover_i": 12646537,
    "edition_count": 143,
    "number_of_pages_median": 397,
    "ratings_average": 4.139456,
    "first_sentence": [
      "When I wake up, the other side of the bed is cold."
    ],
    "subject": [
      "severe poverty",
      "starvation",
      "oppression",
      "effects of war",
      "self-sacrifice",
      "Science fiction",
      "Apocalyptic fiction",
      "Dystopian fiction",
      "Fiction",
      "Juvenile works"
    ]
  },
  {
    "key": "/works/OL17930368W",
    "title": "Atomic Habits",
    "author_name": ["James Clear"],
    "first_publish_year": 2016,
    "cover_i": 12539702,
    "edition_count": 42,
    "number_of_pages_median": 322,
    "ratings_average": 3.9692514,
    "subject": [
      "Habit",
      "Habit breaking",
      "Behavior modification",
      "Self-actualization (psychology)",
      "Business",
      "psychology",
      "Personal Growth",
      "New York Times bestseller",
      "BUSINESS & ECONOMICS / Organizational Behavior",
      "PSYCHOLOGY / Social Psychology"
    ]
  },
  {
    "key": "/works/OL498556W",
    "title": "Metamorphosis",
    "author_name": ["Franz Kafka"],
    "first_publish_year": 1915,
    "cover_i": 12820198,
    "edition_count": 957,
    "number_of_pages_median": 88,
    "ratings_average": 4.1734695,
    "first_sentence": [
      "When Gregor Samsa awoke one morning from disturbing dreams, he found himself transformed . . .",
      "One morning, as Gregor Samsa was waking up from anxious dreams, he discovered that in bed he had been changed into a monstrous verminous bug.",
      "A literary classic is a work of the highest excellence that has something important to say about life and/or the human condition and says it with great artistry.",
      "I COULD HEAR THE CARTS driving past the garden fence, sometimes I even caught sight of them through the gently shifting gaps in the foliage."
    ],
    "subject": [
      "Fantasy fiction",
      "Children's fiction",
      "Lectures et morceaux choisis",
      "Fantastique",
      "Nouvelles",
      "M\u00e9tamorphose",
      "Romans",
      "Famille",
      "Metamorphosis",
      "Die Verwandlung"
    ]
  },
  {
    "key": "/works/OL1168007W",
    "title": "Animal Farm",
    "author_name": ["George Orwell"],
    "first_publish_year": 1945,
    "cover_i": 11261770,
    "edition_count": 657,
    "number_of_pages_median": 128,
    "ratings_average": 4.171799,
    "first_sentence": [
      "MR. JONES, of the Manor Farm, had locked the hen-houses for the night, but was too drunk to remember to shut the popholes.",
      "Nous sommes \u00e0 la ferme, \u00e0 la tomb\u00e9e de la nuit, alors que M.Jones vient de rentrer du pub.",
      "O sr. Jones, propriet\u00e1rio da Fazenda do Solar, fechara o galinheiro \u00e0 noite, mas estava b\u00eabado demais para se lembrar de fechar as portinholas.",
      "MR JONES, of the Manor Farm, had locked the hen-houses for the night, but was too drunk to remember to shut the pop-holes.",
      "Den H\u00fchnnerstall seines Gutshofes hatte Mr. Jones am Abend abgeschlossen, aber so betrunken, wie er war, hatte er nicht daran gedacht, auch die Ausschlupfklappen zu schlie\u00dfen."
    ],
    "subject": [
      "British and Irish fiction (fictional works by one author)",
      "political satire",
      "classic",
      "animal drama",
      "Fiction, political",
      "English literature: literary criticism",
      "For National Curriculum Key Stage 4 & GCSE",
      "Fiction, satire",
      "English literature",
      "Fiction"
    ]
  }
];

/* ---- Field extraction, mirroring OpenLibraryBookService.toBook() ---- */

window.toVanGoghBook = function (doc) {
  var workKey = (doc.key || "").replace(/^\/works\//, "") || "book-0";
  var title = doc.title || "Untitled";
  var author = (doc.author_name && doc.author_name.length) ? doc.author_name[0] : "";
  var year = doc.first_publish_year || 0;
  var rating = doc.ratings_average || 0.0;
  var subjects = (doc.subject && doc.subject.length)
      ? doc.subject.slice(0, 3).join(" \u00b7 ")
      : "";
  var firstSentence = (doc.first_sentence && doc.first_sentence.length)
      ? pickEnglish(doc.first_sentence)
      : "";
  var pages = doc.number_of_pages_median || 0;
  var editionCount = doc.edition_count || 0;
  var coverUrl = doc.cover_i
      ? "https://covers.openlibrary.org/b/id/" + doc.cover_i + "-L.jpg"
      : "";
  return {
    workKey: workKey, title: title, author: author, year: year,
    rating: rating, subjects: subjects, firstSentence: firstSentence,
    pages: pages, editionCount: editionCount, coverUrl: coverUrl
  };
};

/* first_sentence pools every translation; the English line is usually the
 * ASCII-only entry — same rule as OpenLibraryBookService.pickEnglish(). */
function pickEnglish(sentences) {
  for (var i = 0; i < sentences.length; i++) {
    if (isAscii(sentences[i])) return sentences[i];
  }
  return sentences[0];
}

function isAscii(s) {
  for (var i = 0; i < s.length; i++) {
    if (s.charCodeAt(i) >= 128) return false;
  }
  return true;
}

/* Cover loading chain — same failover as the app: direct first, then the
 * wsrv.nl proxy (used when the direct host is unreachable on this machine). */
window.coverSources = function (coverUrl) {
  if (!coverUrl) return [];
  var proxied = "https://wsrv.nl/?url=" + encodeURIComponent(coverUrl);
  return [coverUrl, proxied];
};
