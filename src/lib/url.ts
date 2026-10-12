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

  if (url.protocol !== "https:" && !(options.allowInsecureHttp && url.protocol === "http:")) {
    throw new Error("Server URL must use https://");
  }

  url.hash = "";
  url.search = "";

  return url.toString().replace(/\/$/, "");
}
