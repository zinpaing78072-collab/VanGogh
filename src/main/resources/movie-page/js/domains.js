/* The domain registry: one row per content form, the whole per-domain
 * contract. A new form is a row file plus one line here.
 */
import { movieDomain } from "./domains/movie.js";
import { gameDomain } from "./domains/game.js";
import { musicDomain } from "./domains/music.js";
import { bookDomain } from "./domains/book.js";

export const DOMAINS = Object.fromEntries(
    [movieDomain, gameDomain, musicDomain, bookDomain].map((domain) => [domain.key, domain]));

export const DOMAIN_KEYS = Object.keys(DOMAINS);
