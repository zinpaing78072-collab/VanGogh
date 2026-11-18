/* Page-shell elements, looked up once.
 *
 * Module scripts are deferred, so they run after the HTML is parsed — the
 * lookups belong here rather than inside a DOMContentLoaded handler.
 */
export const grid = document.getElementById("movieGrid");
export const searchInput = document.getElementById("searchInput");
export const searchBtn = document.getElementById("searchBtn");
export const contentFilter = document.getElementById("contentFilter");
export const contentFilterMenu = document.getElementById("contentFilterMenu");
export const contentFilterLabel = document.getElementById("contentFilterLabel");
export const sectionTitle = document.getElementById("sectionTitle");
