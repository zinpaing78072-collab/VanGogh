import { describe, expect, it } from "vitest";
import { youtubeEmbedUrl, youtubeVideoId } from "./youtube";

describe("youtubeVideoId", () => {
  it("reads the watch, share and embed forms", () => {
    expect(youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).toBe(
      "dQw4w9WgXcQ"
    );
    expect(youtubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=42")).toBe("dQw4w9WgXcQ");
    expect(youtubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ?rel=0")).toBe(
      "dQw4w9WgXcQ"
    );
    expect(youtubeVideoId("https://m.youtube.com/shorts/dQw4w9WgXcQ")).toBe(
      "dQw4w9WgXcQ"
    );
  });

  it("accepts the privacy-enhanced host and bare hosts", () => {
    expect(youtubeVideoId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ")).toBe(
      "dQw4w9WgXcQ"
    );
    expect(youtubeVideoId("https://youtube.com/watch?v=dQw4w9WgXcQ")).toBe("dQw4w9WgXcQ");
  });

  it("returns nothing for other hosts and bad ids", () => {
    expect(youtubeVideoId("https://vimeo.com/12345")).toBe("");
    expect(youtubeVideoId("https://www.youtube.com/watch?v=short")).toBe("");
    expect(youtubeVideoId("https://www.youtube.com/watch")).toBe("");
    expect(youtubeVideoId("javascript:alert(1)")).toBe("");
    expect(youtubeVideoId("not a url")).toBe("");
    expect(youtubeVideoId("")).toBe("");
    expect(youtubeVideoId(null)).toBe("");
  });
});

describe("youtubeEmbedUrl", () => {
  it("builds the embed player URL", () => {
    expect(youtubeEmbedUrl("dQw4w9WgXcQ")).toBe(
      "https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1&rel=0&playsinline=1"
    );
  });
});
