// Prototype-only interactions: sidebar collapse, screenshot lightbox,
// read-more clamps and the two save buttons. No IPC, no backend.

document.addEventListener("DOMContentLoaded", () => {
  // Sidebar collapse — same class the app's shell uses.
  const sidebar = document.querySelector(".sidebar");
  const sidebarToggle = document.querySelector(".sidebar-toggle");
  if (sidebar && sidebarToggle) {
    sidebarToggle.addEventListener("click", () => {
      const collapsed = sidebar.classList.toggle("collapsed");
      sidebarToggle.setAttribute("aria-label", collapsed ? "Expand sidebar" : "Collapse sidebar");
      sidebarToggle.setAttribute("title", collapsed ? "Expand sidebar" : "Collapse sidebar");
    });
  }

  // Read-more clamps. Markup: <button class="read-more" data-clamp="overview">…
  document.querySelectorAll(".read-more[data-clamp]").forEach((button) => {
    const target = document.getElementById(button.dataset.clamp);
    if (!target) return;
    button.addEventListener("click", () => {
      const open = target.classList.toggle("is-open");
      button.setAttribute("aria-expanded", String(open));
      const label = button.querySelector("span");
      if (label) label.textContent = open ? "Show less" : button.dataset.moreLabel || "Read more";
    });
  });

  // Save buttons are local state only. Both the "Add to My Lists" and the
  // heart flip their own label; nothing is persisted.
  document.querySelectorAll(".js-save").forEach((button) => {
    const offLabel = button.dataset.offLabel || button.textContent.trim();
    const onLabel = button.dataset.onLabel || offLabel;
    button.setAttribute("aria-pressed", "false");
    button.addEventListener("click", () => {
      const on = button.classList.toggle("is-on");
      button.setAttribute("aria-pressed", String(on));
      const label = button.querySelector("span");
      if (label) label.textContent = on ? onLabel : offLabel;
      else button.textContent = on ? onLabel : offLabel;
    });
  });

  // Screenshot lightbox. Markup: <button class="shot" data-full="…" data-caption="…">
  let lightbox = null;

  function buildLightbox() {
    lightbox = document.createElement("div");
    lightbox.className = "lightbox";
    lightbox.setAttribute("role", "dialog");
    lightbox.setAttribute("aria-modal", "true");
    lightbox.setAttribute("aria-label", "Screenshot preview");
    lightbox.innerHTML = `
      <button type="button" class="lightbox-close" aria-label="Close preview">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
      </button>
      <div>
        <img alt="">
        <p class="lightbox-caption"></p>
      </div>`;
    document.body.appendChild(lightbox);
    lightbox.addEventListener("click", (event) => {
      if (event.target === lightbox || event.target.closest(".lightbox-close")) close();
    });
  }

  function open(full, alt, caption) {
    if (!lightbox) buildLightbox();
    const img = lightbox.querySelector("img");
    img.src = full;
    img.alt = alt || "Game screenshot";
    lightbox.querySelector(".lightbox-caption").textContent = caption || "";
    lightbox.hidden = false;
    document.body.style.overflow = "hidden";
    lightbox.querySelector(".lightbox-close").focus();
  }

  function close() {
    if (!lightbox) return;
    lightbox.hidden = true;
    document.body.style.overflow = "";
  }

  document.querySelectorAll("[data-full]").forEach((button) => {
    button.addEventListener("click", () => open(
      button.dataset.full,
      button.querySelector("img")?.alt,
      button.dataset.caption
    ));
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") close();
  });
});
