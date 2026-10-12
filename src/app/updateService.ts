import { CapacitorHttp, type HttpResponse } from "@capacitor/core";

export const UPDATE_RELEASES_URL = "https://github.com/jp2creation/FriCamGate/releases";

const LATEST_RELEASE_API_URL = "https://api.github.com/repos/jp2creation/FriCamGate/releases/latest";

export type UpdateStatus = "idle" | "checking" | "current" | "available" | "unavailable" | "error";

export interface UpdateCheckResult {
  status: Exclude<UpdateStatus, "idle" | "checking">;
  checkedAt: string;
  currentVersion: string;
  latestVersion?: string;
  releaseUrl?: string;
  message: string;
}

interface GitHubRelease {
  tag_name?: string;
  name?: string;
  html_url?: string;
}

type GitHubReleaseResponse = Omit<HttpResponse, "data"> & {
  data?: GitHubRelease;
};

export async function checkForAppUpdate(currentVersion: string): Promise<UpdateCheckResult> {
  const checkedAt = new Date().toISOString();
  const response = (await CapacitorHttp.request({
    method: "GET",
    url: LATEST_RELEASE_API_URL,
    headers: {
      Accept: "application/vnd.github+json"
    },
    responseType: "json"
  })) as GitHubReleaseResponse;

  if (response.status === 403 || response.status === 404) {
    return {
      status: "unavailable",
      checkedAt,
      currentVersion,
      releaseUrl: UPDATE_RELEASES_URL,
      message: "Releases GitHub non accessibles depuis l'app."
    };
  }

  if (response.status >= 400) {
    return {
      status: "error",
      checkedAt,
      currentVersion,
      releaseUrl: UPDATE_RELEASES_URL,
      message: "Recherche de mise a jour impossible."
    };
  }

  const latestVersion = extractVersion(response.data?.tag_name ?? response.data?.name ?? "");
  if (!latestVersion) {
    return {
      status: "error",
      checkedAt,
      currentVersion,
      releaseUrl: response.data?.html_url ?? UPDATE_RELEASES_URL,
      message: "Version GitHub introuvable."
    };
  }

  const releaseUrl = response.data?.html_url ?? UPDATE_RELEASES_URL;
  if (compareVersions(latestVersion, currentVersion) > 0) {
    return {
      status: "available",
      checkedAt,
      currentVersion,
      latestVersion,
      releaseUrl,
      message: `Mise a jour ${latestVersion} disponible.`
    };
  }

  return {
    status: "current",
    checkedAt,
    currentVersion,
    latestVersion,
    releaseUrl,
    message: "Application a jour."
  };
}

export function extractVersion(input: string): string {
  const normalized = input.trim().replace(/^v/i, "");
  const [version] = normalized.split("-");

  return /^\d+(?:\.\d+){0,3}$/.test(version) ? version : "";
}

export function compareVersions(left: string, right: string): number {
  const leftParts = extractVersion(left).split(".").map(Number);
  const rightParts = extractVersion(right).split(".").map(Number);
  const length = Math.max(leftParts.length, rightParts.length);

  for (let index = 0; index < length; index += 1) {
    const leftValue = leftParts[index] ?? 0;
    const rightValue = rightParts[index] ?? 0;

    if (leftValue > rightValue) {
      return 1;
    }

    if (leftValue < rightValue) {
      return -1;
    }
  }

  return 0;
}
