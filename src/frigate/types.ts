export interface CameraSummary {
  name: string;
  friendlyName: string;
  enabled: boolean;
  latestFrameUrl: string;
  liveStreamUrl: string;
}

export interface EventSummary {
  id: string;
  camera: string;
  label: string;
  time: string;
  previewUrl: string;
  thumbnailUrl: string;
}

export interface StoredSession {
  profileId: string;
  baseUrl: string;
  authToken: string;
  username: string;
  role?: string;
}

export interface LoginResult {
  authToken: string;
  username: string;
  role?: string;
}

export interface FrigateConfig {
  cameras?: Record<
    string,
    {
      enabled?: boolean;
      name?: string;
      friendly_name?: string;
      live?: {
        streams?: Record<string, string>;
      };
    }
  >;
}

export interface FrigateEvent {
  id: string;
  camera: string;
  label?: string;
  sub_label?: string | null;
  start_time?: number;
  end_time?: number | null;
  has_snapshot?: boolean;
  thumbnail?: string;
}
