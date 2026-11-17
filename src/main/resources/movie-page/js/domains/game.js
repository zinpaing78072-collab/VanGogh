/* Game row: TheGamesDB trending/search, front-boxart portrait cards. */
import { posterCard } from "../cards.js";
import { posterDetail } from "./poster-detail.js";

export const gameDomain = {
    key: "game",
    request: "requestGames",
    data: "pollGameData",
    art: "pollGamePosters",
    event: "gamePoster",
    plural: "Games",
    emptyLabel: "games",
    cardClass: "movie-card game-card",
    artClass: "card-poster",
    idField: "id",
    card: (item, idx, onOpen) => posterCard(gameDomain, item, idx, onOpen),
    detail: posterDetail,
};
