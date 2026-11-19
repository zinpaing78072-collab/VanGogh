/* Page-shell elements, looked up once.
 *
 * Module scripts are deferred, so they run after the HTML is parsed — the
 * lookups belong here rather than inside a DOMContentLoaded handler.
 */
export const grid = document.getElementById("movieGrid");
export const searchInput = document.getElementById("searchInput");
export const searchBtn = document.getElementById("searchBtn");
export const aiToggle = document.getElementById("aiToggle");
export const sectionTitle = document.getElementById("sectionTitle");
export const sidebar = document.getElementById("sidebar");
export const sidebarToggle = document.getElementById("sidebarToggle");
export const sidebarNav = document.getElementById("sidebarNav");
