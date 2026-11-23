import { useEffect, useRef, useState } from "react";

/**
 * Sidebar collapse. Below NARROW_WIDTH the rail would leave the grid too
 * little room, so it starts collapsed and follows later resizes — until the
 * user picks a state with the toggle, which wins from then on.
 *
 * The 900px step is the same boundary as the grid ladder's 3-column step in
 * base.css: change one, change both.
 */
export const NARROW_WIDTH = 900;

export function useNarrowSidebar(): {
  collapsed: boolean;
  toggle: () => void;
} {
  const [collapsed, setCollapsed] = useState(false);
  const userChose = useRef(false);

  useEffect(() => {
    const apply = () => {
      if (!userChose.current) setCollapsed(window.innerWidth < NARROW_WIDTH);
    };
    window.addEventListener("resize", apply);
    // Krema can size the webview after the module runs, and no resize event
    // always reaches the page, so observe the document as well.
    const observer = new ResizeObserver(apply);
    observer.observe(document.documentElement);
    apply();
    return () => {
      window.removeEventListener("resize", apply);
      observer.disconnect();
    };
  }, []);

  const toggle = () => {
    userChose.current = true;
    setCollapsed((current) => !current);
  };

  return { collapsed, toggle };
}
