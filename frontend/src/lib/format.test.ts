import { describe, expect, it } from "vitest";
import {
  formatCount,
  formatStat,
  formatTime,
  isHexColor,
  messageOf,
  secureLink,
} from "./format";

describe("messageOf", () => {
  it("prefers a non-empty message property", () => {
    expect(messageOf(new Error("boom"))).toBe("boom");
  });

  it("falls back to String() for empty or missing messages", () => {
    expect(messageOf({ message: "" })).toBe("[object Object]");
    expect(messageOf("plain")).toBe("plain");
    expect(messageOf(undefined)).toBe("undefined");
  });
});

describe("formatTime", () => {
  it("pads the seconds and leaves the minutes uncapped", () => {
    expect(formatTime(252)).toBe("4:12");
    expect(formatTime(5)).toBe("0:05");
    expect(formatTime(3725)).toBe("62:05");
  });

  it("clamps junk to zero", () => {
    expect(formatTime(null)).toBe("0:00");
    expect(formatTime("")).toBe("0:00");
    expect(formatTime(-9)).toBe("0:00");
  });
});

describe("formatCount", () => {
  it("uses the compact forms", () => {
    expect(formatCount(224)).toBe("224");
    expect(formatCount(81_200)).toBe("81K");
    expect(formatCount(2_364_330)).toBe("2.4M");
  });

  it("shows an en dash for nothing", () => {
    expect(formatCount(0)).toBe("–");
    expect(formatCount(undefined)).toBe("–");
  });
});

describe("formatStat", () => {
  it("groups thousands and hides zero", () => {
    expect(formatStat(2_364_330)).toBe("2,364,330");
    expect(formatStat(0)).toBe("");
    expect(formatStat(null)).toBe("");
  });
});

describe("secureLink", () => {
  it("only passes http(s)", () => {
    expect(secureLink("https://openlibrary.org/works/OL1W")).toBe(
      "https://openlibrary.org/works/OL1W"
    );
    expect(secureLink("HTTP://example.com")).toBe("HTTP://example.com");
  });

  it("rejects everything else", () => {
    expect(secureLink("javascript:alert(1)")).toBe("");
    expect(secureLink("//example.com")).toBe("");
    expect(secureLink(null)).toBe("");
  });
});

describe("isHexColor", () => {
  it("accepts 3-8 hex digits", () => {
    expect(isHexColor("#fff")).toBe(true);
    expect(isHexColor("#1a2b3c")).toBe(true);
    expect(isHexColor("#1a2b3c80")).toBe(true);
  });

  it("rejects named colours and injections", () => {
    expect(isHexColor("red")).toBe(false);
    expect(isHexColor("#12")).toBe(false);
    expect(isHexColor("#gggggg")).toBe(false);
    expect(isHexColor("red; background: url(x)")).toBe(false);
  });
});
