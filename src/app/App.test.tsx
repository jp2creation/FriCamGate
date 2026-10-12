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
  CapacitorCookies: {
    deleteCookie: mocks.deleteCookie,
    setCookie: mocks.setCookie
  },
  CapacitorHttp: {
    request: mocks.request
  }
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
    window.localStorage.clear();
  });

  it("renders the FriCamGate setup screen", () => {
    render(<App />);

    expect(screen.getByRole("heading", { name: /FriCamGate/i })).toBeInTheDocument();
    expect(screen.getByAltText(/FriCamGate logo/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/server url/i)).toHaveValue("");
    expect(screen.getByRole("button", { name: /test connection/i })).toBeDisabled();
  });

  it("shows a camera placeholder when authenticated live frame loading fails", async () => {
    mocks.storedSession = {
      profileId: "default",
      baseUrl: "http://frigate.example.com",
      authToken: "token-value",
      username: "admin"
    };
    mocks.request.mockImplementation(({ responseType, url }: { responseType?: string; url: string }) => {
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

      if (responseType === "arraybuffer" && url.includes("/latest.jpg")) {
        return Promise.reject(new Error("Live frame unavailable"));
      }

      return Promise.reject(new Error(`Unexpected request: ${url}`));
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByTestId("camera-card")).toBeInTheDocument();
    });
    expect(screen.queryByAltText("Live frame for Devant")).not.toBeInTheDocument();
    expect(screen.queryByText("live")).not.toBeInTheDocument();
    await waitFor(() => {
      expect(mocks.request).toHaveBeenCalledWith(
        expect.objectContaining({
          responseType: "arraybuffer",
          url: expect.stringContaining("http://frigate.example.com/api/devant/latest.jpg")
        })
      );
    });
    await waitFor(() => {
      expect(mocks.request).toHaveBeenCalledWith(
        expect.objectContaining({
          responseType: "arraybuffer",
          url: expect.stringContaining("http://frigate.example.com/api/devant/latest.jpg")
        })
      );
    });
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

  it("uses authenticated live camera frames and lets settings hide cameras", async () => {
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
              devant: { enabled: true, friendly_name: "Devant" },
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

      if (responseType === "arraybuffer" && url.includes("/api/devant/latest.jpg")) {
        return Promise.resolve({
          status: 200,
          headers: { "content-type": "image/jpeg" },
          url,
          data: "bGl2ZQ=="
        });
      }

      if (responseType === "arraybuffer" && url.includes("/api/allee/latest.jpg")) {
        return Promise.resolve({
          status: 200,
          headers: { "content-type": "image/jpeg" },
          url,
          data: "YWxsZWU="
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

    const liveStream = await screen.findByAltText("Live frame for Devant");
    expect(liveStream).toHaveAttribute("src", "data:image/jpeg;base64,bGl2ZQ==");
    expect(liveStream).not.toHaveAttribute("src", expect.stringContaining("latest.jpg"));
    await waitFor(() => {
      expect(mocks.request).toHaveBeenCalledWith(
        expect.objectContaining({
          responseType: "arraybuffer",
          url: expect.stringContaining("http://frigate.example.com/api/devant/latest.jpg")
        })
      );
    });

    fireEvent.click(screen.getByRole("button", { name: /^Settings$/i }));
    fireEvent.click(await screen.findByRole("button", { name: /Cameras Visibilite/i }));
    fireEvent.click(await screen.findByRole("checkbox", { name: /show allee/i }));
    fireEvent.click(screen.getByRole("button", { name: /^Home$/i }));

    expect(await screen.findByAltText("Live frame for Devant")).toBeInTheDocument();
    expect(screen.queryByAltText("Live frame for Allee")).not.toBeInTheDocument();
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
