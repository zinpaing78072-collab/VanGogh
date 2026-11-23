import { cn } from "../../../lib/cn";
import { formatTime } from "../../../lib/format";
import {
  restartPlayer,
  seekPlayerRatio,
  togglePlayer,
  usePlayer,
} from "./player";

/** The pinned demo transport. Purely visual: no audio element exists. */
export function PlayerBar() {
  const { visible, playing, seconds, duration, title, artist, art } = usePlayer();
  const percent = duration > 0 ? Math.min(100, (seconds / duration) * 100) : 0;

  return (
    <div
      className={cn("player-bar", !visible && "hidden")}
      id="playerBar"
      aria-label="Song player"
    >
      <div className="player-now">
        <div className={cn("player-art", art && "has-art")} id="playerArt">
          {art ? <img src={art} alt="" /> : null}
        </div>
        <div className="player-copy">
          <div className="player-title" id="playerTitle">
            {title}
          </div>
          <div className="player-artist" id="playerArtist">
            {artist}
          </div>
        </div>
      </div>

      <div className="player-center">
        <div className="player-controls">
          <button
            type="button"
            className="player-btn"
            id="playerRestart"
            aria-label="Restart"
            onClick={restartPlayer}
          >
            ↺
          </button>
          <button
            type="button"
            className="player-btn player-play"
            id="playerToggle"
            aria-label={playing ? "Pause" : "Play"}
            onClick={togglePlayer}
          >
            {playing ? "❚❚" : "▶"}
          </button>
        </div>
        <div className="player-progress">
          <span id="playerElapsed">{formatTime(seconds)}</span>
          <div
            className="player-track"
            id="playerTrack"
            role="slider"
            tabIndex={0}
            aria-label="Seek"
            aria-valuemin={0}
            aria-valuemax={duration}
            aria-valuenow={Math.round(seconds)}
            onClick={(event) => {
              const rect = event.currentTarget.getBoundingClientRect();
              if (rect.width <= 0) return;
              seekPlayerRatio((event.clientX - rect.left) / rect.width);
            }}
          >
            <div className="player-fill" id="playerFill" style={{ width: percent + "%" }} />
          </div>
          <span id="playerDuration">{formatTime(duration)}</span>
        </div>
      </div>

      <div className="player-right">
        <span className="demo-badge" title="No audio source is wired yet">
          Demo audio
        </span>
      </div>
    </div>
  );
}
