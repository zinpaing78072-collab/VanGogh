/* Movie row: TMDB trending/search, portrait poster cards. */
import { posterCard } from "../cards.js";
import { posterDetail } from "./poster-detail.js";

export const movieDomain = {
    key: "movie",
    request: "requestMovies",
    data: "pollMovieData",
    art: "pollMoviePosters",
    event: "poster",
    plural: "Movies",
    emptyLabel: "movies",
    cardClass: "movie-card",
    artClass: "card-poster",
    idField: "id",
    card: (item, idx, onOpen) => posterCard(movieDomain, item, idx, onOpen),
    detail: posterDetail,
};
