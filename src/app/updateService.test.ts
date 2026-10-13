import { beforeEach, describe, expect, it, vi } from "vitest";
import { UPDATE_RELEASES_URL, checkForAppUpdate, compareVersions, extractVersion } from "./updateService";

const mocks = vi.hoisted(() => ({
  request: vi.fn()
}));

vi.mock("@capacitor/core", () => ({
  CapacitorHttp: {
    request: mocks.request
  }
}));

describe("updateService", () => {
  beforeEach(() => {
    mocks.request.mockReset();
  });

  it("compares release tags with debug suffixes", () => {
    expect(extractVersion("v0.1.4-debug")).toBe("0.1.4");
    expect(compareVersions("0.1.5", "0.1.4")).toBe(1);
    expect(compareVersions("0.1.4", "0.1.4")).toBe(0);
    expect(compareVersions("0.1.3", "0.1.4")).toBe(-1);
  });

  it("reports an available public GitHub release", async () => {
    mocks.request.mockResolvedValue({
      status: 200,
      headers: {},
      url: "https://api.github.com/repos/jp2creation/FriCamGate/releases?per_page=20",
      data: [
        {
          tag_name: "v0.1.5-debug",
          html_url: "https://github.com/jp2creation/FriCamGate/releases/tag/v0.1.5-debug",
          prerelease: true,
          assets: [
            {
              name: "FriCamGate-v0.1.5-debug.apk",
              browser_download_url: "https://github.com/jp2creation/FriCamGate/releases/download/v0.1.5-debug/FriCamGate-v0.1.5-debug.apk"
            }
          ]
        },
        {
          tag_name: "v0.1.3-debug",
          html_url: "https://github.com/jp2creation/FriCamGate/releases/tag/v0.1.3-debug",
          prerelease: false,
          assets: [{ name: "FriCamGate-v0.1.3-debug.apk" }]
        }
      ]
    });

    await expect(checkForAppUpdate("0.1.4")).resolves.toMatchObject({
      status: "available",
      assetName: "FriCamGate-v0.1.5-debug.apk",
      currentVersion: "0.1.4",
      latestVersion: "0.1.5",
      releaseUrl: "https://github.com/jp2creation/FriCamGate/releases/tag/v0.1.5-debug",
      downloadUrl: "https://github.com/jp2creation/FriCamGate/releases/download/v0.1.5-debug/FriCamGate-v0.1.5-debug.apk"
    });

    expect(mocks.request).toHaveBeenCalledWith(
      expect.objectContaining({
        headers: expect.objectContaining({
          "User-Agent": "FriCamGate"
        }),
        url: "https://api.github.com/repos/jp2creation/FriCamGate/releases?per_page=20"
      })
    );
  });

  it("keeps private GitHub release failures safe and explicit", async () => {
    mocks.request.mockResolvedValue({
      status: 404,
      headers: {},
      url: "https://api.github.com/repos/jp2creation/FriCamGate/releases?per_page=20",
      data: {}
    });

    await expect(checkForAppUpdate("0.1.4")).resolves.toMatchObject({
      status: "unavailable",
      currentVersion: "0.1.4",
      releaseUrl: UPDATE_RELEASES_URL
    });
  });
});
