import { Capacitor, registerPlugin } from "@capacitor/core";

interface AppUpdatePlugin {
  downloadApk(options: AppUpdateDownloadOptions): Promise<{ downloadId?: number }>;
}

interface AppUpdateDownloadOptions {
  description: string;
  fileName: string;
  title: string;
  url: string;
}

interface DownloadAppUpdateOptions {
  assetName?: string;
  downloadUrl: string;
  latestVersion?: string;
}

const AppUpdate = registerPlugin<AppUpdatePlugin>("AppUpdate");

export async function downloadAppUpdate({ assetName, downloadUrl, latestVersion }: DownloadAppUpdateOptions): Promise<void> {
  const fileName = getApkFileName(assetName, downloadUrl, latestVersion);

  if (Capacitor.getPlatform() === "android" && Capacitor.isPluginAvailable("AppUpdate")) {
    await AppUpdate.downloadApk({
      description: "Telechargement de la mise a jour FriCamGate",
      fileName,
      title: latestVersion ? `FriCamGate ${latestVersion}` : "FriCamGate update",
      url: downloadUrl
    });
    return;
  }

  triggerBrowserDownload(downloadUrl, fileName);
}

export function getApkFileName(assetName: string | undefined, downloadUrl: string, latestVersion?: string): string {
  const fromAsset = sanitizeApkFileName(assetName);
  if (fromAsset) {
    return fromAsset;
  }

  try {
    const pathName = new URL(downloadUrl).pathname;
    const urlName = decodeURIComponent(pathName.split("/").filter(Boolean).at(-1) ?? "");
    const fromUrl = sanitizeApkFileName(urlName);
    if (fromUrl) {
      return fromUrl;
    }
  } catch {
    // Fall through to the deterministic versioned name.
  }

  return sanitizeApkFileName(`FriCamGate-v${latestVersion ?? "latest"}-debug.apk`) ?? "FriCamGate-update.apk";
}

function sanitizeApkFileName(fileName?: string): string | null {
  const normalized = fileName?.trim().replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
  if (!normalized?.toLowerCase().endsWith(".apk")) {
    return null;
  }

  return normalized;
}

function triggerBrowserDownload(url: string, fileName: string) {
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.rel = "noopener noreferrer";
  link.style.display = "none";
  document.body.append(link);
  link.click();
  link.remove();
}
