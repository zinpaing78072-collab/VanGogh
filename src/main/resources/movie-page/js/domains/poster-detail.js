/* Detail text for the portrait-poster forms (movie and game). Both use the
 * same card shape and the same "what is this" answer, so the formatter lives
 * here once; a form that needs its own wording gets its own function. */

export function posterDetail(item) {
    const title = item.title || item.game_title || "Untitled";
    const year = item.year || (item.release_date ? item.release_date.slice(0, 4) : "–");
    return { title: `Selected: ${title} (${year})`, body: item.overview || "" };
}
