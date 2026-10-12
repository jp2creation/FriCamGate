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

  it("builds real live stream and event preview URLs", () => {
    const api = new FrigateApi("http://frigate.example.com");

    expect(api.liveStreamUrl("porte entree")).toBe(
      "http://frigate.example.com/api/go2rtc/api/stream.mjpeg?src=porte%20entree"
    );
    expect(api.eventPreviewUrl("event-1")).toBe("http://frigate.example.com/api/events/event-1/preview.gif");

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
});
