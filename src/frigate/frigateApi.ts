import { CapacitorHttp, type HttpOptions, type HttpResponse } from "@capacitor/core";
import type { CameraSummary, EventSummary, FrigateConfig, FrigateEvent, LoginResult } from "./types";

type FrigateHttpResponse<T> = Omit<HttpResponse, "data"> & {
  data?: T;
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

  mapCameras(config: FrigateConfig): CameraSummary[] {
    return Object.entries(config.cameras ?? {}).map(([name, camera]) => ({
      name,
      friendlyName: camera.friendly_name ?? toTitle(name),
      enabled: camera.enabled !== false,
      latestFrameUrl: this.latestFrameUrl(name),
      liveAge: "live"
    }));
  }

  mapEvents(events: FrigateEvent[]): EventSummary[] {
    return events.map((event) => ({
      id: event.id,
      camera: event.camera,
      label: event.sub_label ?? event.label ?? "motion",
      time: formatEventTime(event.start_time),
      thumbnailUrl: this.eventSnapshotUrl(event.id)
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
