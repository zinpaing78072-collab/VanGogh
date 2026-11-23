import type { ReactNode } from "react";
import { cn } from "../../lib/cn";
import type { DomainKey } from "../../app/types";
import { DOMAINS } from "../../bridge/domains";

interface SidebarProps {
  collapsed: boolean;
  onToggle: () => void;
  activeDomain: DomainKey;
  view: "browse" | "detail" | "settings";
  onSelect: (domain: DomainKey) => void;
  onSettings: () => void;
}

const ICONS: Record<string, ReactNode> = {
  home: (
    <>
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5 9.5V21h14V9.5" />
    </>
  ),
  music: (
    <>
      <path d="M9 18V5l12-2v13" />
      <circle cx="6" cy="18" r="3" />
      <circle cx="18" cy="16" r="3" />
    </>
  ),
  movie: (
    <>
      <rect x="2" y="4" width="20" height="16" rx="2" />
      <path d="M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5" />
    </>
  ),
  book: (
    <>
      <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
      <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
    </>
  ),
  game: (
    <>
      <rect x="2" y="6" width="20" height="12" rx="4" />
      <path d="M6.5 12h4M8.5 10v4" />
      <path d="M16 10.5h.01M18.5 13.5h.01" />
    </>
  ),
  lists: (
    <>
      <path d="M8 6h13M8 12h13M8 18h13" />
      <path d="M3.5 6h.01M3.5 12h.01M3.5 18h.01" />
    </>
  ),
  favorites: (
    <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l8.8 8.6 8.8-8.6a5.5 5.5 0 0 0 0-7.8z" />
  ),
  settings: (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </>
  ),
};

function NavIcon({ name }: { name: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {ICONS[name]}
    </svg>
  );
}

interface NavItemProps {
  icon: string;
  label: string;
  collapsed: boolean;
  active?: boolean;
  inert?: boolean;
  id?: string;
  dataDomain?: DomainKey;
  dataNav?: string;
  onClick?: () => void;
}

function NavItem({
  icon,
  label,
  collapsed,
  active = false,
  inert = false,
  id,
  dataDomain,
  dataNav,
  onClick,
}: NavItemProps) {
  return (
    <button
      type="button"
      className={cn("nav-item", active && "active")}
      id={id}
      data-domain={dataDomain}
      data-nav={dataNav}
      aria-disabled={inert ? "true" : undefined}
      aria-current={active ? "page" : undefined}
      aria-label={collapsed ? label : undefined}
      title={collapsed ? label : ""}
      onClick={inert ? undefined : onClick}
    >
      <NavIcon name={icon} />
      <span className="nav-text">{label}</span>
    </button>
  );
}

export function Sidebar({
  collapsed,
  onToggle,
  activeDomain,
  view,
  onSelect,
  onSettings,
}: SidebarProps) {
  const onSettingsView = view === "settings";
  const action = collapsed ? "Expand sidebar" : "Collapse sidebar";

  return (
    <aside
      className={cn("sidebar", collapsed && "collapsed")}
      id="sidebar"
      aria-label="Sidebar"
    >
      <div className="sidebar-head">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            ✶
          </span>
          <span className="brand-text">Van Gogh</span>
        </div>
        <button
          type="button"
          id="sidebarToggle"
          className="icon-btn sidebar-toggle"
          aria-controls="sidebar"
          aria-expanded={!collapsed}
          aria-label={action}
          title={action}
          onClick={onToggle}
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <path d="M9 4v16" />
          </svg>
        </button>
      </div>

      <nav className="sidebar-nav" id="sidebarNav" aria-label="Main navigation">
        <p className="nav-label">Menu</p>

        <NavItem icon="home" label="Home" collapsed={collapsed} inert dataNav="home" />

        {(["music", "movie", "book", "game"] as DomainKey[]).map((key) => (
          <NavItem
            key={key}
            icon={key}
            label={DOMAINS[key].label}
            collapsed={collapsed}
            active={!onSettingsView && key === activeDomain}
            dataDomain={key}
            onClick={() => onSelect(key)}
          />
        ))}

        <p className="nav-label nav-label-spaced">Library</p>

        <NavItem icon="lists" label="My Lists" collapsed={collapsed} inert dataNav="lists" />
        <NavItem
          icon="favorites"
          label="Favorites"
          collapsed={collapsed}
          inert
          dataNav="favorites"
        />
      </nav>

      <div className="sidebar-foot">
        <NavItem
          icon="settings"
          label="Settings"
          collapsed={collapsed}
          active={onSettingsView}
          id="settingsNav"
          dataNav="settings"
          onClick={onSettings}
        />
      </div>
    </aside>
  );
}
