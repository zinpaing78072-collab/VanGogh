/* Small helpers shared by the cards and the renderer. */

export function escapeHtml(text) {
    if (text == null) return "";
    const div = document.createElement("div");
    div.textContent = text;
    return div.innerHTML;
}

export function messageOf(error) {
    return error && error.message ? error.message : String(error);
}

// Posters stream in as data: URIs, so  elements start with no src and the
// placeholder is visible. If an img carrying a real src fails, fall back to
// the placeholder.
export function posterFailed(img) {
    img.removeAttribute("src");
    img.classList.add("hidden");
    const placeholder = img.parentElement.querySelector(".poster-placeholder");
    if (placeholder) {
        placeholder.classList.remove("hidden");
    }
}
