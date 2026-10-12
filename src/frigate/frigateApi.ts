import { CapacitorCookies, CapacitorHttp, type HttpOptions, type HttpResponse } from "@capacitor/core";
import type { CameraSummary, EventSummary, FrigateConfig, FrigateEvent, LoginResult } from "./types";

type FrigateHttpResponse<T> = Omit<HttpResponse, "data"> & {
  data?: T;
};

type FrigateImageResponse = Omit<HttpResponse, "data"> & {
  data?: string | ArrayBuffer | number[] | Uint8Array;
};

export class FrigateApiError extends Error {
  constructor(
    message: string,
    readonly status?: number
  ) {
    super(message);
  }
}

export class FrigateApi {
  constructor(readonly baseUrl: string) {}

  async login(user: string, password: string): Promise<LoginResult> {
    const response = await this.request<{ token?: string; access_token?: string; username?: string; role?: string }>({
      method: "POST",
      path: "/api/login",
      data: { user, password },
      publicRequest: true
    });

    const authToken = response.data?.token ?? response.data?.access_token ?? extractTokenFromHeaders(response.headers);
    if (!authToken) {
      throw new FrigateApiError("Login accepted but no token was returned", response.status);
    }

    return {
      authToken,
      username: response.data?.username ?? user,
      role: response.data?.role
    };
  }

  async authenticate(authToken: string): Promise<{ username?: string; role?: string }> {
    const response = await this.request<unknown>({
      method: "GET",
      path: "/api/auth",
      authToken
    });

    return {
      username: getHeader(response.headers, "remote-user"),
      role: getHeader(response.headers, "remote-role")
    };
  }

  async getConfig(authToken: string): Promise<FrigateConfig> {
    const response = await this.request<FrigateConfig>({
      method: "GET",
      path: "/api/config",
      authToken
    });

    return response.data ?? {};
  }

  async getEvents(authToken: string, limit = 25): Promise<FrigateEvent[]> {
    const response = await this.request<FrigateEvent[]>({
      method: "GET",
      path: `/api/events?limit=${limit}&include_thumbnails=1`,
      authToken
    });

    return Array.isArray(response.data) ? response.data : [];
  }

  latestFrameUrl(cameraName: string): string {
    return `${this.baseUrl}/api/${encodeURIComponent(cameraName)}/latest.jpg?h=360`;
  }

  eventSnapshotUrl(eventId: string): string {
    return `${this.baseUrl}/api/events/${encodeURIComponent(eventId)}/snapshot.jpg?h=180`;
  }

  eventPreviewUrl(eventId: string): string {
    return `${this.baseUrl}/api/events/${encodeURIComponent(eventId)}/preview.gif`;
  }

  liveStreamUrl(cameraName: string): string {
    return `${this.baseUrl}/api/go2rtc/api/stream.mjpeg?src=${encodeURIComponent(cameraName)}`;
  }

  async syncAuthCookie(authToken: string): Promise<void> {
    await CapacitorCookies.setCookie({
      url: this.baseUrl,
      key: "frigate_token",
      value: authToken,
      path: "/"
    });
  }

  async clearAuthCookie(): Promise<void> {
    await CapacitorCookies.deleteCookie({
      url: this.baseUrl,
      key: "frigate_token"
    });
  }

  async getImageDataUrl(authToken: string, url: string): Promise<string> {
    const response = (await CapacitorHttp.request({
      method: "GET",
      url,
      headers: {
        Accept: "image/*",
        Authorization: `Bearer ${authToken}`
      },
      responseType: "arraybuffer"
    })) as FrigateImageResponse;

    if (response.status >= 400) {
      throw new FrigateApiError(response.status === 401 ? "Image Frigate non autorisee" : "Image Frigate indisponible", response.status);
    }

    const contentType = getHeader(response.headers, "content-type")?.split(";")[0].trim() || "image/jpeg";
    return toImageDataUrl(response.data, contentType);
  }

  mapCameras(config: FrigateConfig): CameraSummary[] {
    return Object.entries(config.cameras ?? {}).map(([name, camera]) => ({
      name,
      friendlyName: camera.friendly_name ?? toTitle(name),
      enabled: camera.enabled !== false,
      latestFrameUrl: this.latestFrameUrl(name),
      liveStreamUrl: this.liveStreamUrl(name)
    }));
  }

  mapEvents(events: FrigateEvent[]): EventSummary[] {
    return events.map((event) => ({
      id: event.id,
      camera: event.camera,
      label: event.sub_label ?? event.label ?? "motion",
      time: formatEventTime(event.start_time),
      previewUrl: this.eventPreviewUrl(event.id),
      thumbnailUrl: event.thumbnail ? toImageDataUrl(event.thumbnail) : this.eventSnapshotUrl(event.id)
    }));
  }

  private async request<T>({
    method,
    path,
    data,
    authToken,
    publicRequest = false
  }: {
    method: "GET" | "POST";
    path: string;
    data?: unknown;
    authToken?: string;
    publicRequest?: boolean;
  }): Promise<FrigateHttpResponse<T>> {
    const options: HttpOptions = {
      method,
      url: `${this.baseUrl}${path}`,
      headers: {
        Accept: "application/json",
        ...(data ? { "Content-Type": "application/json" } : {}),
        ...(!publicRequest && authToken ? { Authorization: `Bearer ${authToken}` } : {})
      },
      data,
      responseType: "json"
    };

    const response = await CapacitorHttp.request(options);
    if (response.status >= 400) {
      throw new FrigateApiError(response.status === 401 ? "Identifiants refuses" : "Frigate request failed", response.status);
    }

    return response as FrigateHttpResponse<T>;
  }
}

export function toImageDataUrl(data: FrigateImageResponse["data"], contentType = "image/jpeg"): string {
  if (typeof data === "string") {
    const trimmed = data.trim();
    if (trimmed.startsWith("data:")) {
      return trimmed;
    }

    return `data:${contentType};base64,${trimmed.replace(/\s/g, "")}`;
  }

  if (data instanceof ArrayBuffer) {
    return `data:${contentType};base64,${base64FromBytes(new Uint8Array(data))}`;
  }

  if (data instanceof Uint8Array) {
    return `data:${contentType};base64,${base64FromBytes(data)}`;
  }

  if (Array.isArray(data)) {
    return `data:${contentType};base64,${base64FromBytes(Uint8Array.from(data))}`;
  }

  throw new FrigateApiError("Image Frigate vide");
}

function base64FromBytes(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }

  return btoa(binary);
}

function extractTokenFromHeaders(headers: HttpResponse["headers"]): string | undefined {
  const cookie = getHeader(headers, "set-cookie") ?? getHeader(headers, "Set-Cookie");
  return cookie?.match(/frigate_token=([^;]+)/)?.[1];
}

function getHeader(headers: HttpResponse["headers"], name: string): string | undefined {
  const found = Object.entries(headers ?? {}).find(([key]) => key.toLowerCase() === name.toLowerCase());
  const value = found?.[1];
  return Array.isArray(value) ? value[0] : value;
}

function toTitle(input: string): string {
  return input
    .replace(/[-_]+/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatEventTime(timestamp?: number): string {
  if (!timestamp) {
    return "recent";
  }

  return new Intl.DateTimeFormat("fr-FR", {
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(timestamp * 1000));
}
