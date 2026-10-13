import { beforeEach, describe, expect, it, vi } from "vitest";
import { FrigateApi, toImageDataUrl } from "./frigateApi";

const mocks = vi.hoisted(() => ({
  deleteCookie: vi.fn(),
  setCookie: vi.fn(),
  request: vi.fn()
}));

vi.mock("@capacitor/core", () => ({
  CapacitorCookies: {
    deleteCookie: mocks.deleteCookie,
    setCookie: mocks.setCookie
  },
  CapacitorHttp: {
    request: mocks.request
  }
}));

describe("FrigateApi image loading", () => {
  beforeEach(() => {
    mocks.deleteCookie.mockReset();
    mocks.request.mockReset();
    mocks.setCookie.mockReset();
  });

  it("loads Frigate images through CapacitorHttp with the bearer token", async () => {
    mocks.request.mockResolvedValue({
      status: 200,
      headers: { "content-type": "image/jpeg" },
      url: "http://frigate.example.com/api/devant/latest.jpg",
      data: "YWJj\n"
    });

    const api = new FrigateApi("http://frigate.example.com");

    await expect(api.getImageDataUrl("token-value", "http://frigate.example.com/api/devant/latest.jpg")).resolves.toBe(
      "data:image/jpeg;base64,YWJj"
    );
    expect(mocks.request).toHaveBeenCalledWith(
      expect.objectContaining({
        method: "GET",
        responseType: "arraybuffer",
        url: "http://frigate.example.com/api/devant/latest.jpg",
        headers: {
          Accept: "image/*",
          Authorization: "Bearer token-value"
        }
      })
    );
  });

  it("keeps existing image data URLs untouched", () => {
    expect(toImageDataUrl("data:image/png;base64,abc", "image/jpeg")).toBe("data:image/png;base64,abc");
  });

  it("uses embedded event thumbnails when Frigate includes them", () => {
    const api = new FrigateApi("http://frigate.example.com");
    const [event] = api.mapEvents([
      {
        id: "event-1",
        camera: "devant",
        label: "person",
        thumbnail: "YWJj"
      }
    ]);

    expect(event.thumbnailUrl).toBe("data:image/jpeg;base64,YWJj");
  });

  it("builds MSE live stream and event preview URLs", () => {
    const api = new FrigateApi("http://frigate.example.com");

    expect(api.liveStreamUrl("porte entree")).toBe(
      "ws://frigate.example.com/live/mse/api/ws?src=porte%20entree"
    );
    expect(new FrigateApi("https://frigate.example.com/nvr").liveStreamUrl("devant")).toBe(
      "wss://frigate.example.com/nvr/live/mse/api/ws?src=devant"
    );
    expect(api.eventPreviewUrl("event-1")).toBe("http://frigate.example.com/api/events/event-1/preview.gif");

    const [camera] = api.mapCameras({
      cameras: {
        devant: {
          friendly_name: "Devant",
          live: {
            streams: {
              Mobile: "devant_sub",
              Main: "devant"
            }
          }
        }
      }
    });

    expect(camera.liveStreamUrl).toBe("ws://frigate.example.com/live/mse/api/ws?src=devant_sub");
    expect(camera.audioStreamUrl).toBe("ws://frigate.example.com/live/mse/api/ws?src=devant");

    const [event] = api.mapEvents([
      {
        id: "event-1",
        camera: "devant",
        label: "person"
      }
    ]);

    expect(event.previewUrl).toBe("http://frigate.example.com/api/events/event-1/preview.gif");
    expect(event.thumbnailUrl).toBe("http://frigate.example.com/api/events/event-1/snapshot.jpg?h=180");
  });

  it("builds event clip and synchronized recording snapshot URLs", () => {
    const api = new FrigateApi("http://frigate.example.com/nvr");

    expect(api.eventClipUrl("event 1")).toBe("http://frigate.example.com/nvr/api/events/event%201/clip.mp4");
    expect(api.recordingSnapshotUrl("porte entree", 1_790_000_042)).toBe(
      "http://frigate.example.com/nvr/api/porte%20entree/recordings/1790000042/snapshot.webp"
    );
  });
});
