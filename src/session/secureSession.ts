import { SecureStorage } from "@aparajita/capacitor-secure-storage";
import type { StoredSession } from "../frigate/types";

const SESSION_KEY = "active-session";

export async function saveSession(session: StoredSession): Promise<void> {
  await SecureStorage.setKeyPrefix("fricamgate_");
  await SecureStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export async function loadSession(): Promise<StoredSession | null> {
  await SecureStorage.setKeyPrefix("fricamgate_");
  const raw = await SecureStorage.getItem(SESSION_KEY);
  const stored = raw ? JSON.parse(raw) : null;
  return isStoredSession(stored) ? stored : null;
}

export async function clearSession(): Promise<void> {
  await SecureStorage.setKeyPrefix("fricamgate_");
  await SecureStorage.remove(SESSION_KEY);
}

function isStoredSession(value: unknown): value is StoredSession {
  if (!value || typeof value !== "object") {
    return false;
  }

  const candidate = value as Partial<StoredSession>;
  return Boolean(candidate.baseUrl && candidate.authToken && candidate.username);
}
