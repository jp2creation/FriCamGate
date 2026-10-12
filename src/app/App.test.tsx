import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const mocks = vi.hoisted(() => ({
  request: vi.fn(),
  storedSession: null as null | {
    profileId: string;
    baseUrl: string;
    authToken: string;
    username: string;
    role?: string;
  }
}));

vi.mock("@capacitor/core", () => ({
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
    mocks.request.mockReset();
    mocks.storedSession = null;
  });

  it("renders the FriCamGate setup screen", () => {
    render(<App />);

    expect(screen.getByRole("heading", { name: /FriCamGate/i })).toBeInTheDocument();
    expect(screen.getByAltText(/FriCamGate logo/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/server url/i)).toHaveValue("");
    expect(screen.getByRole("button", { name: /test connection/i })).toBeDisabled();
  });

  it("loads images after restoring an insecure HTTP session", async () => {
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

    const image = await screen.findByAltText("Latest frame for Devant");
    expect(image).toHaveAttribute("src", "data:image/jpeg;base64,ZGVm");
    await waitFor(() => {
      expect(mocks.request).toHaveBeenCalledWith(
        expect.objectContaining({
          responseType: "arraybuffer",
          url: expect.stringContaining("http://frigate.example.com/api/devant/latest.jpg")
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
      if (url.includes("api.github.com/repos/jp2creation/FriCamGate/releases/latest")) {
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
});
