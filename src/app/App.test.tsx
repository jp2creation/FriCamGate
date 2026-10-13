import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const mocks = vi.hoisted(() => ({
  deleteCookie: vi.fn(),
  request: vi.fn(),
  setCookie: vi.fn(),
  storedSession: null as null | {
    profileId: string;
    baseUrl: string;
    authToken: string;
    username: string;
    role?: string;
  }
}));

vi.mock("@capacitor/core", () => ({
  Capacitor: {
    getPlatform: vi.fn(() => "web"),
    isPluginAvailable: vi.fn(() => false)
  },
  CapacitorCookies: {
    deleteCookie: mocks.deleteCookie,
    setCookie: mocks.setCookie
  },
  CapacitorHttp: {
    request: mocks.request
  },
  registerPlugin: vi.fn(() => ({
    addListener: vi.fn(),
    close: vi.fn(),
    connect: vi.fn(),
    send: vi.fn()
  }))
}));

vi.mock("../session/secureSession", () => ({
  clearSession: vi.fn(),
  loadSession: vi.fn(() => Promise.resolve(mocks.storedSession)),
  saveSession: vi.fn()
}));

describe("App", () => {
  beforeEach(() => {
    mocks.deleteCookie.mockReset();
    mocks.request.mockReset();
    mocks.setCookie.mockReset();
    mocks.storedSession = null;
    vi.unstubAllGlobals();
    window.localStorage.clear();
  });

  it("renders the FriCamGate setup screen", () => {
    render(<App />);

    expect(screen.getByRole("heading", { name: /FriCamGate/i })).toBeInTheDocument();
    expect(screen.getByAltText(/FriCamGate logo/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/server url/i)).toHaveValue("");
    expect(screen.getByRole("button", { name: /test connection/i })).toBeDisabled();
  });

  it("shows the latest camera frame while live video is unavailable", async () => {
    mocks.storedSession = {
      profileId: "default",
      baseUrl: "http://frigate.example.com",
      authToken: "token-value",
      username: "admin"
    };
    mocks.request.mockImplementation(({ url }: { responseType?: string; url: string }) => {
      if (url.endsWith("/api/config")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: { cameras: { devant: { enabled: true, friendly_name: "Devant" } } }
        });
      }

      if (url.includes("/api/events")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: [{ id: "event-1", camera: "devant", label: "person", thumbnail: "YWJj", start_time: 1 }]
        });
      }

      if (url.includes("/api/devant/latest.jpg")) {
        return Promise.resolve({
          status: 200,
          headers: { "content-type": "image/jpeg" },
          url,
          data: "ZGVm"
        });
      }

      return Promise.reject(new Error(`Unexpected request: ${url}`));
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByTestId("camera-card")).toBeInTheDocument();
    });
    expect(await screen.findByAltText("Latest frame for Devant")).toBeInTheDocument();
    expect(screen.queryByText("live")).not.toBeInTheDocument();
    await waitFor(() => {
      expect(mocks.request).toHaveBeenCalledWith(
        expect.objectContaining({
          responseType: "arraybuffer",
          url: "http://frigate.example.com/api/events/event-1/preview.gif"
        })
      );
    });
  });

  it("renders camera.ui style settings rows and subpages", async () => {
    mocks.storedSession = {
      profileId: "default",
      baseUrl: "http://frigate.example.com",
      authToken: "token-value",
      username: "admin",
      role: "viewer"
    };
    mocks.request.mockImplementation(({ url }: { responseType?: string; url: string }) => {
      if (url.includes("api.github.com/repos/jp2creation/FriCamGate/releases")) {
        return Promise.resolve({
          status: 404,
          headers: {},
          url,
          data: {}
        });
      }

      if (url.endsWith("/api/config")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: { cameras: { devant: { enabled: true, friendly_name: "Devant" } } }
        });
      }

      if (url.includes("/api/events")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: []
        });
      }

      return Promise.resolve({
        status: 200,
        headers: { "content-type": "image/jpeg" },
        url,
        data: "ZGVm"
      });
    });

    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: /^Settings$/i }));

    expect(await screen.findByText("Assistant")).toBeInTheDocument();
    expect(screen.getByText("Faces")).toBeInTheDocument();
    expect(screen.getByText("Appearance")).toBeInTheDocument();
    expect(screen.getByText("Permissions")).toBeInTheDocument();
    expect(screen.getByText("Updates")).toBeInTheDocument();
    expect(screen.getByText("Switch server")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /recherche automatique/i })).toBeChecked();
    expect(screen.getByRole("button", { name: /verifier/i })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Appearance/i }));

    expect(await screen.findByRole("heading", { name: "Appearance" })).toBeInTheDocument();
    expect(screen.getByText(/table rows per page/i)).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /System collapsible/i })).toBeChecked();
  });

  it("uses MSE live camera video and lets settings hide cameras", async () => {
    const sockets = installMseMocks();
    mocks.storedSession = {
      profileId: "default",
      baseUrl: "http://frigate.example.com",
      authToken: "token-value",
      username: "admin",
      role: "viewer"
    };
    mocks.request.mockImplementation(({ responseType, url }: { responseType?: string; url: string }) => {
      if (url.includes("api.github.com/repos/jp2creation/FriCamGate/releases")) {
        return Promise.resolve({
          status: 404,
          headers: {},
          url,
          data: {}
        });
      }

      if (url.endsWith("/api/config")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: {
            cameras: {
              devant: {
                enabled: true,
                friendly_name: "Devant",
                live: {
                  streams: {
                    Mobile: "devant_sub",
                    Main: "devant_main"
                  }
                }
              },
              allee: { enabled: true, friendly_name: "Allee" }
            }
          }
        });
      }

      if (url.includes("/api/events")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: []
        });
      }

      if (responseType === "arraybuffer") {
        return Promise.resolve({
          status: 200,
          headers: { "content-type": "image/jpeg" },
          url,
          data: "ZGVm"
        });
      }

      return Promise.reject(new Error(`Unexpected request: ${url}`));
    });

    render(<App />);

    await waitFor(() => {
      expect(sockets).toHaveLength(2);
    });
    expect(await screen.findByAltText("Latest frame for Devant")).toBeInTheDocument();
    expect(sockets[0]?.url).toBe("ws://frigate.example.com/live/mse/api/ws?src=devant_sub");
    sockets[0]?.open();
    expect(sockets[0]?.send).toHaveBeenCalledWith(expect.stringContaining('"type":"mse"'));
    sockets[0]?.message('{"type":"mse","value":"video/mp4; codecs=\\"avc1.64001F\\""}');

    const liveStream = await screen.findByLabelText("Live video for Devant");
    expect(liveStream.tagName).toBe("VIDEO");
    expect(liveStream).toHaveAttribute("src", "blob:fricamgate-live");
    expect(liveStream).not.toHaveAttribute("src", expect.stringContaining("latest.jpg"));
    expect(liveStream).toHaveProperty("muted", true);
    Object.defineProperty(liveStream, "videoWidth", { configurable: true, value: 640 });
    Object.defineProperty(liveStream, "videoHeight", { configurable: true, value: 360 });
    fireEvent.loadedData(liveStream);
    expect(await screen.findByText("live")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Unmute Devant/i }));
    await waitFor(() => {
      expect(sockets).toHaveLength(3);
    });
    expect(sockets[2]?.url).toBe("ws://frigate.example.com/live/mse/api/ws?src=devant_main");
    sockets[2]?.open();
    sockets[2]?.message('{"type":"mse","value":"video/mp4; codecs=\\"avc1.64001F,mp4a.40.2\\""}');
    const audioStream = await screen.findByLabelText("Live video for Devant");
    expect(audioStream).toHaveProperty("muted", false);
    Object.defineProperty(audioStream, "videoWidth", { configurable: true, value: 640 });
    Object.defineProperty(audioStream, "videoHeight", { configurable: true, value: 360 });
    fireEvent.loadedData(audioStream);
    expect(screen.getByRole("button", { name: /Mute Devant/i })).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(screen.getByRole("button", { name: /^Settings$/i }));
    fireEvent.click(await screen.findByRole("button", { name: /Cameras Visibilite/i }));
    fireEvent.click(await screen.findByRole("checkbox", { name: /show allee/i }));
    fireEvent.click(screen.getByRole("button", { name: /^Home$/i }));

    expect(await screen.findByLabelText("Live video for Devant")).toBeInTheDocument();
    expect(screen.queryByLabelText("Live video for Allee")).not.toBeInTheDocument();
  });

  it("opens a camera detail timeline from the home camera card", async () => {
    installMseMocks();
    mocks.storedSession = {
      profileId: "default",
      baseUrl: "http://frigate.example.com",
      authToken: "token-value",
      username: "admin",
      role: "viewer"
    };
    mocks.request.mockImplementation(({ responseType, url }: { responseType?: string; url: string }) => {
      if (url.includes("api.github.com/repos/jp2creation/FriCamGate/releases")) {
        return Promise.resolve({
          status: 404,
          headers: {},
          url,
          data: {}
        });
      }

      if (url.endsWith("/api/config")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: { cameras: { devant: { enabled: true, friendly_name: "Devant" } } }
        });
      }

      if (url.includes("/api/events")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: [
            {
              id: "event-1",
              camera: "devant",
              label: "person",
              thumbnail: "YWJj",
              start_time: 1_790_000_000,
              end_time: 1_790_000_053
            }
          ]
        });
      }

      if (responseType === "arraybuffer") {
        return Promise.resolve({
          status: 200,
          headers: { "content-type": "image/jpeg" },
          url,
          data: "ZGVm"
        });
      }

      return Promise.reject(new Error(`Unexpected request: ${url}`));
    });

    render(<App />);

    const cameraCard = await screen.findByTestId("camera-card");
    fireEvent.click(cameraCard);

    expect(await screen.findByTestId("camera-detail")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Back to home/i })).toBeInTheDocument();
    expect(screen.getByLabelText("Timeline Devant")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Unmute Devant/i })).toBeInTheDocument();
    expect(screen.getByText("53s")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Back to home/i }));
    expect(await screen.findByTestId("camera-card")).toBeInTheDocument();
  });

  it("opens the APK download directly from the update card", async () => {
    const openSpy = vi.spyOn(window, "open").mockImplementation(() => null);
    mocks.storedSession = {
      profileId: "default",
      baseUrl: "http://frigate.example.com",
      authToken: "token-value",
      username: "admin",
      role: "viewer"
    };
    mocks.request.mockImplementation(({ url }: { responseType?: string; url: string }) => {
      if (url.includes("api.github.com/repos/jp2creation/FriCamGate/releases")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: [
            {
              tag_name: "v9.9.9-debug",
              html_url: "https://github.com/jp2creation/FriCamGate/releases/tag/v9.9.9-debug",
              assets: [
                {
                  name: "FriCamGate-v9.9.9-debug.apk",
                  browser_download_url:
                    "https://github.com/jp2creation/FriCamGate/releases/download/v9.9.9-debug/FriCamGate-v9.9.9-debug.apk"
                }
              ]
            }
          ]
        });
      }

      if (url.endsWith("/api/config")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: { cameras: { devant: { enabled: true, friendly_name: "Devant" } } }
        });
      }

      if (url.includes("/api/events")) {
        return Promise.resolve({
          status: 200,
          headers: {},
          url,
          data: []
        });
      }

      return Promise.resolve({
        status: 200,
        headers: { "content-type": "image/jpeg" },
        url,
        data: "ZGVm"
      });
    });

    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: /^Settings$/i }));
    fireEvent.click(await screen.findByRole("button", { name: /Mettre a jour/i }));

    expect(openSpy).toHaveBeenCalledWith(
      "https://github.com/jp2creation/FriCamGate/releases/download/v9.9.9-debug/FriCamGate-v9.9.9-debug.apk",
      "_blank",
      "noopener,noreferrer"
    );
    openSpy.mockRestore();
  });
});

function installMseMocks() {
  const sockets: MockWebSocket[] = [];
  const play = vi.fn(() => Promise.resolve());
  const load = vi.fn();

  Object.defineProperty(HTMLMediaElement.prototype, "play", {
    configurable: true,
    value: play
  });
  Object.defineProperty(HTMLMediaElement.prototype, "load", {
    configurable: true,
    value: load
  });
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: vi.fn(() => "blob:fricamgate-live")
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: vi.fn()
  });

  class MockSourceBuffer extends EventTarget {
    mode = "segments";
    updating = false;
    appendBuffer = vi.fn(() => {
      this.dispatchEvent(new Event("updateend"));
    });
  }

  class MockMediaSource extends EventTarget {
    static isTypeSupported = vi.fn(() => true);
    addSourceBuffer = vi.fn(() => new MockSourceBuffer());

    constructor() {
      super();
      queueMicrotask(() => this.dispatchEvent(new Event("sourceopen")));
    }
  }

  class MockWebSocket extends EventTarget {
    binaryType = "";
    close = vi.fn();
    send = vi.fn();

    constructor(readonly url: string) {
      super();
      sockets.push(this);
    }

    open() {
      this.dispatchEvent(new Event("open"));
    }

    message(data: string | ArrayBuffer) {
      this.dispatchEvent(new MessageEvent("message", { data }));
    }
  }

  vi.stubGlobal("MediaSource", MockMediaSource);
  vi.stubGlobal("WebSocket", MockWebSocket);

  return sockets;
}
