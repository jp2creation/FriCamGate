import { beforeEach, describe, expect, it, vi } from "vitest";
import { FrigateApi, toImageDataUrl } from "./frigateApi";

const mocks = vi.hoisted(() => ({
  request: vi.fn()
}));

vi.mock("@capacitor/core", () => ({
  CapacitorHttp: {
    request: mocks.request
  }
}));

describe("FrigateApi image loading", () => {
  beforeEach(() => {
    mocks.request.mockReset();
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
});
