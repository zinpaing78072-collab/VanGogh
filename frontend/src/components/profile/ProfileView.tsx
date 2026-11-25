import { useEffect, useRef, type ReactNode } from "react";
import {
  cancelProfileEdit,
  closeProfile,
  saveProfileEdit,
  startProfileEdit,
} from "../../app/actions";
import { useStore } from "../../app/store";
import { cn } from "../../lib/cn";
import { formatStat } from "../../lib/format";
import { PROFILE_COLLECTIONS, PROFILE_STATS, type ProfileIcon } from "./profileData";

type IconName = ProfileIcon | "back" | "pencil" | "arrow-right" | "user";

const ICONS: Record<IconName, ReactNode> = {
  back: (
    <>
      <path d="m12 19-7-7 7-7" />
      <path d="M19 12H5" />
    </>
  ),
  pencil: (
    <>
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
    </>
  ),
  "arrow-right": (
    <>
      <path d="M5 12h14" />
      <path d="m12 5 7 7-7 7" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21v-1a8 8 0 0 1 16 0v1" />
    </>
  ),
  heart: (
    <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l8.8 8.6 8.8-8.6a5.5 5.5 0 0 0 0-7.8z" />
  ),
  playlist: (
    <>
      <path d="M21 15V6" />
      <path d="M18.5 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z" />
      <path d="M12 12H3" />
      <path d="M16 6H3" />
      <path d="M12 18H3" />
    </>
  ),
  activity: <path d="M22 12h-4l-3 9L9 3l-3 9H2" />,
  film: (
    <>
      <rect x="2" y="3" width="20" height="18" rx="2" />
      <path d="M7 3v18M17 3v18M2 8h5M2 16h5M17 8h5M17 16h5" />
    </>
  ),
  music: (
    <>
      <path d="M9 18V5l12-2v13" />
      <circle cx="6" cy="18" r="3" />
      <circle cx="18" cy="16" r="3" />
    </>
  ),
  tv: (
    <>
      <rect x="2" y="7" width="20" height="15" rx="2" />
      <path d="m17 2-5 5-5-5" />
    </>
  ),
  book: (
    <>
      <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
      <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
    </>
  ),
};

function Icon({ name }: { name: IconName }) {
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

/**
 * The inline editor is page-local on purpose: the profile store (and My Lists)
 * is not wired, so Save only moves the name/member line held in the store. The
 * inputs are uncontrolled so the preview harness drives them like the Settings
 * form does.
 */
function ProfileEditor({ name, memberSince }: { name: string; memberSince: string }) {
  const nameRef = useRef<HTMLInputElement>(null);
  const memberRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    nameRef.current?.focus();
    nameRef.current?.select();
  }, []);

  return (
    <form
      className="profile-edit-panel"
      id="profileEditPanel"
      aria-label="Edit profile"
      onSubmit={(event) => {
        event.preventDefault();
        saveProfileEdit(nameRef.current?.value ?? "", memberRef.current?.value ?? "");
      }}
    >
      <label className="profile-edit-field">
        <span className="profile-edit-label">Display name</span>
        <input
          className="profile-edit-input"
          id="profileNameInput"
          name="name"
          type="text"
          ref={nameRef}
          defaultValue={name}
          autoComplete="off"
          spellCheck={false}
        />
      </label>

      <label className="profile-edit-field">
        <span className="profile-edit-label">Member since</span>
        <input
          className="profile-edit-input"
          id="profileMemberInput"
          name="memberSince"
          type="text"
          ref={memberRef}
          defaultValue={memberSince}
          autoComplete="off"
          spellCheck={false}
        />
      </label>

      <div className="profile-edit-actions">
        <button type="button" className="profile-btn" onClick={cancelProfileEdit}>
          Cancel
        </button>
        <button type="submit" className="profile-btn primary" id="profileSave">
          Save
        </button>
      </div>

      <p className="profile-edit-hint">
        Session-only for now &mdash; the profile store is not wired yet.
      </p>
    </form>
  );
}

export function ProfileView({ hidden }: { hidden: boolean }) {
  const profile = useStore((state) => state.profile);
  const view = useStore((state) => state.view);
  const domain = useStore((state) => state.activeDomain);
  const open = view === "profile";
  const wasOpen = useRef(false);
  const returnScroll = useRef(0);
  const openedOn = useRef(domain);

  // The page is taller than the grid, so entering it starts at the top. Back
  // restores the covered view's position — unless the user left via the
  // sidebar, where switchDomain owns the target form's scroll.
  useEffect(() => {
    if (open && !wasOpen.current) {
      openedOn.current = domain;
      returnScroll.current = window.scrollY;
      window.scrollTo(0, 0);
    } else if (!open && wasOpen.current && domain === openedOn.current) {
      window.scrollTo(0, returnScroll.current);
    }
    wasOpen.current = open;
  }, [open, domain]);

  return (
    <section
      className={cn("profile-view", hidden && "hidden")}
      id="profileView"
      aria-labelledby="profileName"
    >
      <div className="profile-inner">
        <div className="profile-topbar">
          <button
            type="button"
            className="profile-back"
            id="profileBack"
            onClick={closeProfile}
          >
            <Icon name="back" />
            Back
          </button>

          <button
            type="button"
            className="profile-edit"
            id="editProfile"
            aria-expanded={profile.editing}
            aria-controls="profileEditPanel"
            onClick={profile.editing ? cancelProfileEdit : startProfileEdit}
          >
            <Icon name="pencil" />
            {profile.editing ? "Close editor" : "Edit Profile"}
          </button>
        </div>

        <header className="profile-hero">
          <div className="profile-avatar" aria-hidden="true">
            <Icon name="user" />
          </div>
          <div className="profile-identity">
            <h1 className="profile-name" id="profileName">
              {profile.name}
            </h1>
            <p className="profile-member">Member since {profile.memberSince}</p>
          </div>
        </header>

        {profile.editing ? (
          <ProfileEditor
            key={profile.name}
            name={profile.name}
            memberSince={profile.memberSince}
          />
        ) : null}

        <section className="profile-universe" aria-labelledby="universeTitle">
          <header className="profile-universe-head">
            <h2 className="profile-section-title" id="universeTitle">
              My Entertainment Universe
            </h2>
            <p className="profile-section-sub">
              The stories, songs and worlds you keep close.
            </p>
          </header>

          <ul className="profile-stats">
            {PROFILE_STATS.map((stat) => (
              <li className="profile-stat" key={stat.key} data-stat={stat.key}>
                <span className="profile-stat-icon" aria-hidden="true">
                  <Icon name={stat.icon} />
                </span>
                <span className="profile-stat-copy">
                  <span className="profile-stat-value">{formatStat(stat.value)}</span>
                  <span className="profile-stat-label">{stat.label}</span>
                </span>
              </li>
            ))}
          </ul>

          <h3 className="profile-collections-title">Collections</h3>

          <div className="profile-collection-grid" id="profileCollections">
            {PROFILE_COLLECTIONS.map((collection) => (
              <article
                className="profile-collection"
                key={collection.key}
                data-collection={collection.key}
              >
                <div className="profile-collection-art" aria-hidden="true">
                  <span className="profile-collection-ghost">{collection.count}</span>
                  <span className="profile-collection-mark">
                    <Icon name={collection.icon} />
                  </span>
                </div>
                <div className="profile-collection-body">
                  <h4 className="profile-collection-title">{collection.title}</h4>
                  <p className="profile-collection-count">{collection.count} items</p>
                  <button
                    type="button"
                    className="profile-collection-link"
                    disabled
                    title="Collections are a preview for now"
                  >
                    View collection
                    <Icon name="arrow-right" />
                  </button>
                </div>
              </article>
            ))}
          </div>
        </section>
      </div>
    </section>
  );
}
