import { CapacitorHttp, type HttpResponse } from "@capacitor/core";

export const UPDATE_RELEASES_URL = "https://github.com/jp2creation/FriCamGate/releases";

const RELEASES_API_URL = "https://api.github.com/repos/jp2creation/FriCamGate/releases?per_page=20";
const GITHUB_API_HEADERS = {
  Accept: "application/vnd.github+json",
  "User-Agent": "FriCamGate",
  "X-GitHub-Api-Version": "2022-11-28"
};

export type UpdateStatus = "idle" | "checking" | "current" | "available" | "unavailable" | "error";

export interface UpdateCheckResult {
  status: Exclude<UpdateStatus, "idle" | "checking">;
  checkedAt: string;
  currentVersion: string;
  assetName?: string;
  downloadUrl?: string;
  latestVersion?: string;
  releaseUrl?: string;
  message: string;
}

interface GitHubRelease {
  tag_name?: string;
  name?: string;
  html_url?: string;
  draft?: boolean;
  assets?: GitHubAsset[];
}

interface GitHubAsset {
  name?: string;
  browser_download_url?: string;
}

type GitHubReleaseResponse = Omit<HttpResponse, "data"> & {
  data?: GitHubRelease[];
};

export async function checkForAppUpdate(currentVersion: string): Promise<UpdateCheckResult> {
  const checkedAt = new Date().toISOString();
  const response = (await CapacitorHttp.request({
    method: "GET",
    url: RELEASES_API_URL,
    headers: GITHUB_API_HEADERS,
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

  const latestRelease = findLatestApkRelease(response.data);
  if (!latestRelease) {
    return {
      status: "error",
      checkedAt,
      currentVersion,
      releaseUrl: UPDATE_RELEASES_URL,
      message: "Version GitHub introuvable."
    };
  }

  const latestVersion = latestRelease.version;
  const releaseUrl = latestRelease.release.html_url ?? UPDATE_RELEASES_URL;
  const downloadUrl = latestRelease.asset.browser_download_url;
  if (compareVersions(latestVersion, currentVersion) > 0) {
    return {
      status: "available",
      assetName: latestRelease.asset.name,
      checkedAt,
      currentVersion,
      downloadUrl,
      latestVersion,
      releaseUrl,
      message: `Mise a jour ${latestVersion} disponible.`
    };
  }

  return {
    status: "current",
    assetName: latestRelease.asset.name,
    checkedAt,
    currentVersion,
    downloadUrl,
    latestVersion,
    releaseUrl,
    message: "Application a jour."
  };
}

function findLatestApkRelease(releases: GitHubRelease[] | undefined) {
  const candidates = (Array.isArray(releases) ? releases : [])
    .filter((release) => release.draft !== true)
    .map((release) => {
      const asset = findApkAsset(release);
      return {
        asset,
        release,
        version: extractVersion(release.tag_name ?? release.name ?? "")
      };
    })
    .filter((release): release is { asset: GitHubAsset; release: GitHubRelease; version: string } =>
      Boolean(release.asset && release.version)
    );

  return candidates.sort((left, right) => compareVersions(right.version, left.version))[0] ?? null;
}

function findApkAsset(release: GitHubRelease): GitHubAsset | undefined {
  return release.assets?.find((asset) => asset.name?.toLowerCase().endsWith(".apk") && asset.browser_download_url);
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
