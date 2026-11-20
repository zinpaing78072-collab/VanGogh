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
export const settingsNavItem = document.getElementById("settingsNav");

/* Settings view — the AI configuration form. Painted/closed by settings.js,
 * shown/hidden by render.js from state.view. */
export const settingsView = document.getElementById("settingsView");
export const settingsForm = document.getElementById("settingsForm");
/* Appearance card — chips are painted by js/theme.js from the loadTheme reply. */
export const themeChips = document.getElementById("themeChips");
export const themeStatus = document.getElementById("themeStatus");
export const settingsBaseUrl = document.getElementById("settingsBaseUrl");
export const settingsModel = document.getElementById("settingsModel");
export const settingsApiKey = document.getElementById("settingsApiKey");
export const settingsReveal = document.getElementById("settingsReveal");
export const settingsTimeout = document.getElementById("settingsTimeout");
export const settingsTitles = document.getElementById("settingsTitles");
export const settingsStatus = document.getElementById("settingsStatus");
export const settingsPath = document.getElementById("settingsPath");
export const settingsSave = document.getElementById("settingsSave");
export const settingsCancel = document.getElementById("settingsCancel");

/* Browse vs. song detail: browseView holds the card grid, songView is painted
 * by song-detail.js, playerBar is the demo transport pinned to the bottom. */
export const browseView = document.getElementById("browseView");
export const songView = document.getElementById("songView");
export const playerBar = document.getElementById("playerBar");
export const playerArt = document.getElementById("playerArt");
export const playerTitle = document.getElementById("playerTitle");
export const playerArtist = document.getElementById("playerArtist");
export const playerToggle = document.getElementById("playerToggle");
export const playerRestart = document.getElementById("playerRestart");
export const playerElapsed = document.getElementById("playerElapsed");
export const playerDuration = document.getElementById("playerDuration");
export const playerTrack = document.getElementById("playerTrack");
export const playerFill = document.getElementById("playerFill");
