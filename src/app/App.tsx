import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Bell, Grid3X3, Home, Image, Loader2, LogOut, RefreshCcw, Settings, Video } from "lucide-react";
import { FrigateApi } from "../frigate/frigateApi";
import type { CameraSummary, EventSummary, StoredSession } from "../frigate/types";
import { normalizeServerUrl } from "../lib/url";
import { clearSession, loadSession, saveSession } from "../session/secureSession";

const logoUrl = new URL("../../assets/brand/fricamgate-logo.png", import.meta.url).href;
const defaultServerUrl = "";

const mockCameras: CameraSummary[] = [
  {
    name: "allee",
    friendlyName: "Allee",
    enabled: true,
    latestFrameUrl: "",
    liveAge: "4s"
  },
  {
    name: "devant",
    friendlyName: "Devant",
    enabled: true,
    latestFrameUrl: "",
    liveAge: "live"
  },
  {
    name: "salon",
    friendlyName: "Salon",
    enabled: true,
    latestFrameUrl: "",
    liveAge: "37s"
  }
];

const mockEvents: EventSummary[] = [
  { id: "1", camera: "devant", label: "person", time: "09:16", thumbnailUrl: "" },
  { id: "2", camera: "allee", label: "car", time: "09:14", thumbnailUrl: "" },
  { id: "3", camera: "salon", label: "motion", time: "09:12", thumbnailUrl: "" }
];

type View = "home" | "cameras" | "grid" | "events" | "settings";
type ConnectionStatus = "idle" | "restoring" | "connecting" | "ready" | "error";

export function App() {
  const [serverUrl, setServerUrl] = useState(defaultServerUrl);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [allowInsecureHttp, setAllowInsecureHttp] = useState(false);
  const [session, setSession] = useState<StoredSession | null>(null);
  const [status, setStatus] = useState<ConnectionStatus>("restoring");
  const [message, setMessage] = useState("Connexion au coffre local...");
  const [view, setView] = useState<View>("home");
  const [cameras, setCameras] = useState<CameraSummary[]>(mockCameras);
  const [events, setEvents] = useState<EventSummary[]>(mockEvents);

  const canConnect = serverUrl.trim().length > 0 && username.trim().length > 0 && password.length > 0 && status !== "connecting";

  useEffect(() => {
    let mounted = true;

    loadSession()
      .then(async (stored) => {
        if (!mounted) {
          return;
        }
        if (!stored) {
          setStatus("idle");
          setMessage("Ajoute ton serveur Frigate pour commencer.");
          return;
        }

        setSession(stored);
        setServerUrl(stored.baseUrl);
        setAllowInsecureHttp(stored.baseUrl.startsWith("http://"));
        setStatus("ready");
        setMessage(`Session restauree pour ${stored.username}.`);
        await refreshDashboard(stored);
      })
      .catch(() => {
        if (mounted) {
          setStatus("idle");
          setMessage("Stockage local pret.");
        }
      });

    return () => {
      mounted = false;
    };
  }, []);

  const api = useMemo(() => {
    if (session) {
      return new FrigateApi(session.baseUrl);
    }

    try {
      return new FrigateApi(normalizeServerUrl(serverUrl, { allowInsecureHttp }));
    } catch {
      return null;
    }
  }, [allowInsecureHttp, serverUrl, session]);

  async function connect() {
    setStatus("connecting");
    setMessage("Test de connexion Frigate...");

    try {
      const baseUrl = normalizeServerUrl(serverUrl, { allowInsecureHttp });
      const frigate = new FrigateApi(baseUrl);
      const login = await frigate.login(username.trim(), password);
      const auth = await frigate.authenticate(login.authToken);
      const nextSession: StoredSession = {
        profileId: "default",
        baseUrl,
        authToken: login.authToken,
        username: auth.username ?? login.username,
        role: auth.role ?? login.role
      };

      await saveSession(nextSession);
      setPassword("");
      setSession(nextSession);
      setServerUrl(baseUrl);
      setStatus("ready");
      setMessage(`Connecte a ${baseUrl}`);
      await refreshDashboard(nextSession);
    } catch (error) {
      setPassword("");
      setStatus("error");
      setMessage(error instanceof Error ? error.message : "Connexion impossible.");
    }
  }

  async function refreshDashboard(activeSession = session) {
    if (!activeSession) {
      return;
    }

    try {
      const frigate = new FrigateApi(activeSession.baseUrl);
      const [config, rawEvents] = await Promise.all([
        frigate.getConfig(activeSession.authToken),
        frigate.getEvents(activeSession.authToken)
      ]);
      const nextCameras = frigate.mapCameras(config);
      setCameras(nextCameras.length > 0 ? nextCameras : mockCameras);
      setEvents(frigate.mapEvents(rawEvents));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Tableau de bord indisponible.");
    }
  }

  async function disconnect() {
    await clearSession();
    setSession(null);
    setStatus("idle");
    setView("home");
    setCameras(mockCameras);
    setEvents(mockEvents);
    setMessage("Session effacee.");
  }

  return (
    <main className="app-shell">
      <header className="app-header">
        <div className="brand">
          <img src={logoUrl} alt="FriCamGate logo" className="brand-logo" />
          <div>
            <p className="eyebrow">Client Frigate</p>
            <h1>FriCamGate</h1>
          </div>
        </div>
        <button className="icon-button" aria-label="Notifications">
          <Bell size={22} />
        </button>
      </header>

      {!session && status !== "ready" ? (
        <section className="setup-panel" aria-label="Server setup">
          <div>
            <p className="eyebrow">Serveur</p>
            <h2>Connecte ton Frigate</h2>
            <p className="muted">Chaque utilisateur renseigne son propre serveur. Aucun identifiant n'est integre a l'APK.</p>
          </div>

          <label>
            <span>Server URL</span>
            <input
              value={serverUrl}
              onChange={(event) => setServerUrl(event.target.value)}
              inputMode="url"
              placeholder="https://frigate.example.com"
            />
          </label>
          <label>
            <span>Username</span>
            <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
          </label>
          <label>
            <span>Password</span>
            <input
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              type="password"
              autoComplete="current-password"
            />
          </label>
          <label className="toggle-row">
            <input
              type="checkbox"
              checked={allowInsecureHttp}
              onChange={(event) => setAllowInsecureHttp(event.target.checked)}
            />
            <span>Autoriser HTTP non securise</span>
          </label>
          {allowInsecureHttp ? (
            <p className="warning-line">
              Attention: en HTTP, le serveur et les identifiants peuvent transiter en clair. Utilise HTTPS des que possible.
            </p>
          ) : null}
          <button className="primary-button" disabled={!canConnect} onClick={connect}>
            {status === "connecting" ? <Loader2 className="spin" size={18} /> : <RefreshCcw size={18} />}
            Test connection
          </button>
          <p className={`status-line ${status === "error" ? "is-error" : ""}`}>{message}</p>
        </section>
      ) : (
        <>
          <Dashboard
            view={view}
            session={session}
            cameras={cameras}
            events={events}
            message={message}
            api={api}
            onRefresh={() => refreshDashboard()}
            onDisconnect={disconnect}
          />
          <BottomNav view={view} onViewChange={setView} />
        </>
      )}
    </main>
  );
}

function Dashboard({
  view,
  session,
  cameras,
  events,
  message,
  api,
  onRefresh,
  onDisconnect
}: {
  view: View;
  session: StoredSession | null;
  cameras: CameraSummary[];
  events: EventSummary[];
  message: string;
  api: FrigateApi | null;
  onRefresh: () => void;
  onDisconnect: () => void;
}) {
  if (view === "settings") {
    return (
      <section className="settings-panel">
        <h2>Reglages</h2>
        <dl>
          <div>
            <dt>Serveur</dt>
            <dd>{session?.baseUrl ?? "-"}</dd>
          </div>
          <div>
            <dt>Utilisateur</dt>
            <dd>{session?.username ?? "-"}</dd>
          </div>
          <div>
            <dt>Role</dt>
            <dd>{session?.role ?? "viewer"}</dd>
          </div>
        </dl>
        <button className="secondary-button" onClick={onRefresh}>
          <RefreshCcw size={18} />
          Rafraichir
        </button>
        <button className="danger-button" onClick={onDisconnect}>
          <LogOut size={18} />
          Log out
        </button>
        <p className="status-line">{message}</p>
      </section>
    );
  }

  if (view === "events") {
    return (
      <section className="content-block">
        <SectionTitle title="Evenements" subtitle="Dernieres detections Frigate" />
        <div className="event-list">
          {events.map((event) => (
            <article className="event-row" key={event.id}>
              <EventThumb event={event} api={api} authToken={session?.authToken} />
              <div>
                <strong>{event.label}</strong>
                <span>
                  {event.camera} - {event.time}
                </span>
              </div>
            </article>
          ))}
        </div>
      </section>
    );
  }

  const gridClass = view === "grid" ? "camera-grid is-dense" : "camera-grid";

  return (
    <section className="content-block">
      <EventStrip events={events} api={api} authToken={session?.authToken} />
      <SectionTitle title={view === "cameras" ? "Cameras" : "Accueil"} subtitle={message} />
      <div className={gridClass}>
        {cameras.map((camera) => (
          <CameraCard camera={camera} key={camera.name} api={api} authToken={session?.authToken} />
        ))}
      </div>
    </section>
  );
}

function SectionTitle({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <div className="section-title">
      <h2>{title}</h2>
      <p>{subtitle}</p>
    </div>
  );
}

function EventStrip({ events, api, authToken }: { events: EventSummary[]; api: FrigateApi | null; authToken?: string }) {
  return (
    <div className="event-strip" aria-label="Recent events">
      {events.slice(0, 8).map((event) => (
        <div className="event-chip" key={event.id}>
          <EventThumb event={event} api={api} authToken={authToken} />
          <span>{event.time}</span>
        </div>
      ))}
    </div>
  );
}

function CameraCard({ camera, api, authToken }: { camera: CameraSummary; api: FrigateApi | null; authToken?: string }) {
  const [refreshKey, setRefreshKey] = useState(0);
  const frameUrl = camera.latestFrameUrl || api?.latestFrameUrl(camera.name);
  const frameRequestUrl = frameUrl ? withCacheBust(frameUrl, refreshKey) : "";

  return (
    <article className="camera-card" data-testid="camera-card">
      <AuthenticatedImage
        alt={`Latest frame for ${camera.friendlyName}`}
        api={api}
        authToken={authToken}
        className="camera-image"
        fallback={<div className="camera-placeholder" />}
        url={frameRequestUrl}
      />
      <div className="camera-overlay">
        <span className="pill">{camera.friendlyName}</span>
        <span className="age-badge">{camera.liveAge}</span>
      </div>
      <div className="quick-actions">
        <button className="floating-button" aria-label={`Refresh ${camera.friendlyName}`} onClick={() => setRefreshKey((key) => key + 1)}>
          <RefreshCcw size={18} />
        </button>
        <button className="floating-button" aria-label={`Open ${camera.friendlyName}`}>
          <Video size={18} />
        </button>
        <button className="floating-button" aria-label={`Settings ${camera.friendlyName}`}>
          <Settings size={18} />
        </button>
      </div>
    </article>
  );
}

function EventThumb({ event, api, authToken }: { event: EventSummary; api: FrigateApi | null; authToken?: string }) {
  return (
    <AuthenticatedImage
      alt={`${event.label} on ${event.camera}`}
      api={api}
      authToken={authToken}
      className="event-thumb"
      fallback={<div className="event-thumb event-thumb-empty" />}
      url={event.thumbnailUrl}
    />
  );
}

function AuthenticatedImage({
  alt,
  api,
  authToken,
  className,
  fallback,
  url
}: {
  alt: string;
  api: FrigateApi | null;
  authToken?: string;
  className: string;
  fallback: ReactNode;
  url?: string;
}) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    setDataUrl(null);
    setFailed(false);

    if (!api || !authToken || !url) {
      setFailed(true);
      return () => {
        active = false;
      };
    }

    if (url.startsWith("data:")) {
      setDataUrl(url);
      return () => {
        active = false;
      };
    }

    api
      .getImageDataUrl(authToken, url)
      .then((nextDataUrl) => {
        if (active) {
          setDataUrl(nextDataUrl);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    return () => {
      active = false;
    };
  }, [api, authToken, url]);

  if (!dataUrl || failed) {
    return <>{fallback}</>;
  }

  return <img className={className} src={dataUrl} alt={alt} />;
}

function withCacheBust(url: string, value: number): string {
  const separator = url.includes("?") ? "&" : "?";
  return `${url}${separator}_=${value}`;
}

function BottomNav({ view, onViewChange }: { view: View; onViewChange: (view: View) => void }) {
  const items: Array<{ view: View; label: string; icon: typeof Home }> = [
    { view: "home", label: "Home", icon: Home },
    { view: "cameras", label: "Cameras", icon: Video },
    { view: "grid", label: "Grid", icon: Grid3X3 },
    { view: "events", label: "Events", icon: Image },
    { view: "settings", label: "Settings", icon: Settings }
  ];

  return (
    <nav className="bottom-nav" aria-label="Primary">
      {items.map((item) => {
        const Icon = item.icon;
        return (
          <button
            className={item.view === view ? "nav-item is-active" : "nav-item"}
            key={item.view}
            onClick={() => onViewChange(item.view)}
            aria-label={item.label}
          >
            <Icon size={23} />
          </button>
        );
      })}
    </nav>
  );
}
