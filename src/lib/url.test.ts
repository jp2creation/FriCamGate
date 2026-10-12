import { describe, expect, it } from "vitest";
import { normalizeServerUrl } from "./url";

describe("normalizeServerUrl", () => {
  it("normalizes an HTTPS Frigate server URL", () => {
    expect(normalizeServerUrl(" https://frigate.example.com/ ")).toBe("https://frigate.example.com");
  });

  it("rejects empty, protocol-less, and insecure URLs by default", () => {
    expect(() => normalizeServerUrl("")).toThrow(/server url/i);
    expect(() => normalizeServerUrl("frigate.example.com")).toThrow(/https/i);
    expect(() => normalizeServerUrl("http://cam.local")).toThrow(/https/i);
  });

  it("allows HTTP only when explicit local lab mode is enabled", () => {
    expect(normalizeServerUrl("http://cam.local", { allowInsecureHttp: true })).toBe("http://cam.local");
    expect(normalizeServerUrl("http://192.168.0.144:8971/", { allowInsecureHttp: true })).toBe("http://192.168.0.144:8971");
    expect(() => normalizeServerUrl("http://frigate.example.com", { allowInsecureHttp: true })).toThrow(/local/i);
  });
});
