/* Entry module. Imports wiring, subscribes artwork, seeds the first render.
 *
 * The page talks to Java through window.krema (Krema's native bridge, or
 * krema-shim.js over cefQuery in the JCEF shell):
 *
 *   invoke("requestMovies" | "requestGames" | "requestMusic" | "requestBooks",
 *          { kind, query })  → request generation, kicks off the IO-pool fetch
 *   invoke("pollMovieData" | "pollGameData" | "pollMusicData" | "pollBookData",
 *          { generation })   → { status: "pending" | "done" | "error" | "superseded",
 *                               items?, error? }
 *   invoke("pollMoviePosters" | "pollGamePosters" | "pollMusicPosters" |
 *          "pollBookCovers", {}) → artwork drain progress (-1 when done)
 *   invoke("requestSongDetail", { id }) → song-detail generation
 *   invoke("pollSongDetail", { generation })
 *          → { status, detail?, artDataUri?, error? }
 *   on("poster" | "gamePoster" | "musicPoster" | "bookCover", { payload })
 *
 * The drains run as synchronous commands on the UI thread — the Windows Krema
 * constraint: engine.eval is dropped off the UI thread, so replies and events
 * must be delivered from it.
 */
import "./ui.js";
import { subscribeArtwork } from "./artwork.js";
import { loadContent } from "./content.js";

subscribeArtwork();
loadContent("movie", "trending", "");
