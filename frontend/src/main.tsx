import "./styles/tailwind.css";
import "./styles/base.css";
import "./styles/music-detail.css";
import "./styles/movie-detail.css";
import "./styles/book-detail.css";
import "./styles/game-detail.css";
import "./styles/settings.css";
import { createRoot } from "react-dom/client";
import { App } from "./app/App";
import { initTheme } from "./theme/theme";

// The stored theme first: the page is already painted with the default sheet,
// and this swaps in the saved skin while the grid is still empty. A failure is
// non-fatal (initTheme swallows it and keeps the default).
await initTheme();

const root = document.getElementById("root");
if (root) {
  createRoot(root).render(<App />);
}
