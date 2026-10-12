export interface NormalizeServerUrlOptions {
  allowInsecureHttp?: boolean;
}

export function normalizeServerUrl(input: string, options: NormalizeServerUrlOptions = {}): string {
  const trimmed = input.trim();

  if (!trimmed) {
    throw new Error("Server URL is required");
  }

  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    throw new Error("Server URL must include https://");
  }

  if (url.protocol === "http:" && options.allowInsecureHttp && !isLocalHost(url.hostname)) {
    throw new Error("HTTP is only allowed for local Frigate servers");
  }

  if (url.protocol !== "https:" && !(options.allowInsecureHttp && url.protocol === "http:")) {
    throw new Error("Server URL must use https://");
  }

  url.hash = "";
  url.search = "";

  return url.toString().replace(/\/$/, "");
}

function isLocalHost(hostname: string): boolean {
  const host = hostname.toLowerCase();

  if (host === "localhost" || host.endsWith(".local")) {
    return true;
  }

  const parts = host.split(".").map(Number);
  if (parts.length !== 4 || parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return false;
  }

  return (
    parts[0] === 10 ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168) ||
    (parts[0] === 127)
  );
}
