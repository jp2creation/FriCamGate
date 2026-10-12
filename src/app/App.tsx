import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Activity,
  AlertCircle,
  ArrowLeft,
  Bell,
  BellRing,
  Bot,
  BrainCircuit,
  CheckCircle2,
  ChevronRight,
  ChevronDown,
  Database,
  Eye,
  EyeOff,
  ExternalLink,
  Grid3X3,
  Home,
  Image,
  Info,
  Languages,
  Loader2,
  Map,
  MapPin,
  Mic,
  PackageCheck,
  Palette,
  PanelTop,
  Plus,
  Power,
  Puzzle,
  RefreshCcw,
  RotateCw,
  Rows3,
  ScanFace,
  Server,
  ServerCog,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  UserCircle,
  Video,
  Workflow
} from "lucide-react";
import { APP_NAME, APP_VERSION } from "./appInfo";
import {
  UPDATE_RELEASES_URL,
  checkForAppUpdate,
  type UpdateCheckResult,
  type UpdateStatus
} from "./updateService";
import { MjpegLiveVideo } from "./MjpegLiveVideo";
import { MseLiveVideo } from "./MseLiveVideo";
import { FrigateApi } from "../frigate/frigateApi";
import type { CameraSummary, EventSummary, StoredSession } from "../frigate/types";
import { normalizeServerUrl } from "../lib/url";
import { canUseNativeLive } from "../native/frigateLive";
import { clearSession, loadSession, saveSession } from "../session/secureSession";

const logoUrl = new URL("../../assets/brand/fricamgate-logo.png", import.meta.url).href;
const defaultServerUrl = "";
const autoUpdateChecksKey = "fricamgate_auto_update_checks";
const hiddenCamerasKeyPrefix = "fricamgate_hidden_cameras:";

const mockCameras: CameraSummary[] = [
  {
    name: "allee",
    friendlyName: "Allee",
    enabled: true,
    latestFrameUrl: "",
    liveStreamUrl: ""
  },
  {
    name: "devant",
    friendlyName: "Devant",
    enabled: true,
    latestFrameUrl: "",
    liveStreamUrl: ""
  },
  {
    name: "salon",
    friendlyName: "Salon",
    enabled: true,
    latestFrameUrl: "",
    liveStreamUrl: ""
  }
];

const mockEvents: EventSummary[] = [
  { id: "1", camera: "devant", label: "person", time: "09:16", previewUrl: "", thumbnailUrl: "" },
  { id: "2", camera: "allee", label: "car", time: "09:14", previewUrl: "", thumbnailUrl: "" },
  { id: "3", camera: "salon", label: "motion", time: "09:12", previewUrl: "", thumbnailUrl: "" }
];

type View = "home" | "cameras" | "grid" | "events" | "settings";
type ConnectionStatus = "idle" | "restoring" | "connecting" | "ready" | "error";
type SettingsPage = "main" | "account" | "appearance" | "permissions" | "notifications" | "faces" | "cameras";

function loadAutoUpdateChecks() {
  try {
    return window.localStorage.getItem(autoUpdateChecksKey) !== "0";
  } catch {
    return true;
  }
}

function hiddenCamerasKey(baseUrl: string) {
  return `${hiddenCamerasKeyPrefix}${baseUrl}`;
}

function loadHiddenCameraNames(baseUrl?: string): string[] {
  if (!baseUrl) {
    return [];
  }

  try {
    const stored = window.localStorage.getItem(hiddenCamerasKey(baseUrl));
    const parsed = stored ? JSON.parse(stored) : [];
    return Array.isArray(parsed) ? parsed.filter((name): name is string => typeof name === "string") : [];
  } catch {
    return [];
  }
}

function saveHiddenCameraNames(baseUrl: string, names: string[]) {
  try {
    window.localStorage.setItem(hiddenCamerasKey(baseUrl), JSON.stringify(names));
  } catch {
    // The preference remains active for this runtime even when storage is unavailable.
  }
}

export function App() {
  const [serverUrl, setServerUrl] = useState(defaultServerUrl);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [allowInsecureHttp, setAllowInsecureHttp] = useState(false);
  const [session, setSession] = useState<StoredSession | null>(null);
  const [status, setStatus] = useState<ConnectionStatus>("restoring");
  const [message, setMessage] = useState("Connexion au coffre local...");
  const [view, setView] = useState<View>("home");
  const [settingsPage, setSettingsPage] = useState<SettingsPage>("main");
  const [cameras, setCameras] = useState<CameraSummary[]>(mockCameras);
  const [hiddenCameraNames, setHiddenCameraNames] = useState<string[]>([]);
  const [events, setEvents] = useState<EventSummary[]>(mockEvents);
  const [autoUpdateChecks, setAutoUpdateChecks] = useState(loadAutoUpdateChecks);
  const [updateResult, setUpdateResult] = useState<UpdateCheckResult | null>(null);
  const [updateStatus, setUpdateStatus] = useState<UpdateStatus>("idle");

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
        setHiddenCameraNames(loadHiddenCameraNames(stored.baseUrl));
        setStatus("ready");
        setMessage(`Session restauree pour ${stored.username}.`);
        await new FrigateApi(stored.baseUrl).syncAuthCookie(stored.authToken).catch(() => undefined);
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

  useEffect(() => {
    if (autoUpdateChecks) {
      void checkUpdates();
    }
  }, [autoUpdateChecks]);

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
      await frigate.syncAuthCookie(login.authToken).catch(() => undefined);
      setPassword("");
      setSession(nextSession);
      setServerUrl(baseUrl);
      setHiddenCameraNames(loadHiddenCameraNames(baseUrl));
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
    if (session) {
      await new FrigateApi(session.baseUrl).clearAuthCookie().catch(() => undefined);
    }
    await clearSession();
    setSession(null);
    setStatus("idle");
    setView("home");
    setSettingsPage("main");
    setCameras(mockCameras);
    setHiddenCameraNames([]);
    setEvents(mockEvents);
    setMessage("Session effacee.");
  }

  async function checkUpdates() {
    setUpdateStatus("checking");

    try {
      const result = await checkForAppUpdate(APP_VERSION);
      setUpdateResult(result);
      setUpdateStatus(result.status);
    } catch {
      setUpdateStatus("error");
      setUpdateResult({
        status: "error",
        checkedAt: new Date().toISOString(),
        currentVersion: APP_VERSION,
        releaseUrl: UPDATE_RELEASES_URL,
        message: "Recherche de mise a jour impossible."
      });
    }
  }

  function toggleAutoUpdateChecks(enabled: boolean) {
    setAutoUpdateChecks(enabled);
    try {
      window.localStorage.setItem(autoUpdateChecksKey, enabled ? "1" : "0");
    } catch {
      // The toggle remains active for this runtime even when storage is unavailable.
    }
  }

  function changeView(nextView: View) {
    setView(nextView);
    if (nextView === "settings") {
      setSettingsPage("main");
    }
  }

  function toggleCameraVisibility(cameraName: string, visible: boolean) {
    const baseUrl = session?.baseUrl;
    setHiddenCameraNames((current) => {
      const next = visible
        ? current.filter((name) => name !== cameraName)
        : Array.from(new Set([...current, cameraName]));
      if (baseUrl) {
        saveHiddenCameraNames(baseUrl, next);
      }
      return next;
    });
  }

  function handleSettingsBack() {
    if (settingsPage === "main") {
      setView("home");
      return;
    }

    setSettingsPage("main");
  }

  const showSettingsHeader = session && status === "ready" && view === "settings" && settingsPage !== "main";
  const visibleCameras = useMemo(
    () => cameras.filter((camera) => !hiddenCameraNames.includes(camera.name)),
    [cameras, hiddenCameraNames]
  );

  return (
    <main className="app-shell">
      {view === "settings" && settingsPage === "main" && session ? null : (
        <header className={showSettingsHeader ? "app-header is-title" : "app-header"}>
          {showSettingsHeader ? (
            <>
              <button className="icon-button" aria-label="Back" onClick={handleSettingsBack} type="button">
                <ArrowLeft size={26} />
              </button>
              <h1 className="screen-title">{getSettingsTitle(settingsPage)}</h1>
              <button className="icon-button" aria-label="Notifications" type="button">
                <Bell size={22} />
              </button>
            </>
          ) : (
            <>
              <div className="brand">
                <img src={logoUrl} alt="FriCamGate logo" className="brand-logo" />
                <div>
                  <p className="eyebrow">Client Frigate</p>
                  <h1>FriCamGate</h1>
                </div>
              </div>
              <button className="icon-button" aria-label="Notifications" type="button">
                <Bell size={22} />
              </button>
            </>
          )}
        </header>
      )}

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
            settingsPage={settingsPage}
            cameras={visibleCameras}
            allCameras={cameras}
            hiddenCameraNames={hiddenCameraNames}
            events={events}
            message={message}
            api={api}
            appVersion={APP_VERSION}
            autoUpdateChecks={autoUpdateChecks}
            updateResult={updateResult}
            updateStatus={updateStatus}
            onRefresh={() => refreshDashboard()}
            onCheckUpdates={checkUpdates}
            onSettingsPageChange={setSettingsPage}
            onToggleCameraVisibility={toggleCameraVisibility}
            onToggleAutoUpdateChecks={toggleAutoUpdateChecks}
            onDisconnect={disconnect}
          />
          <BottomNav view={view} onViewChange={changeView} />
        </>
      )}
    </main>
  );
}

function getSettingsTitle(page: SettingsPage) {
  const titles: Record<SettingsPage, string> = {
    main: "Settings",
    account: "Account",
    appearance: "Appearance",
    permissions: "Permissions",
    notifications: "Notifications",
    faces: "Faces",
    cameras: "Cameras"
  };

  return titles[page];
}

function Dashboard({
  view,
  session,
  settingsPage,
  cameras,
  allCameras,
  hiddenCameraNames,
  events,
  message,
  api,
  appVersion,
  autoUpdateChecks,
  updateResult,
  updateStatus,
  onRefresh,
  onCheckUpdates,
  onSettingsPageChange,
  onToggleCameraVisibility,
  onToggleAutoUpdateChecks,
  onDisconnect
}: {
  view: View;
  session: StoredSession | null;
  settingsPage: SettingsPage;
  cameras: CameraSummary[];
  allCameras: CameraSummary[];
  hiddenCameraNames: string[];
  events: EventSummary[];
  message: string;
  api: FrigateApi | null;
  appVersion: string;
  autoUpdateChecks: boolean;
  updateResult: UpdateCheckResult | null;
  updateStatus: UpdateStatus;
  onRefresh: () => void;
  onCheckUpdates: () => void;
  onSettingsPageChange: (page: SettingsPage) => void;
  onToggleCameraVisibility: (cameraName: string, visible: boolean) => void;
  onToggleAutoUpdateChecks: (enabled: boolean) => void;
  onDisconnect: () => void;
}) {
  if (view === "settings") {
    return (
      <SettingsScreen
        appVersion={appVersion}
        autoUpdateChecks={autoUpdateChecks}
        cameras={allCameras}
        hiddenCameraNames={hiddenCameraNames}
        message={message}
        page={settingsPage}
        session={session}
        updateResult={updateResult}
        updateStatus={updateStatus}
        onCheckUpdates={onCheckUpdates}
        onDisconnect={onDisconnect}
        onRefresh={onRefresh}
        onSettingsPageChange={onSettingsPageChange}
        onToggleCameraVisibility={onToggleCameraVisibility}
        onToggleAutoUpdateChecks={onToggleAutoUpdateChecks}
      />
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
        {cameras.length > 0 ? (
          cameras.map((camera) => (
            <CameraCard authToken={session?.authToken} camera={camera} key={camera.name} api={api} />
          ))
        ) : (
          <div className="empty-camera-state">
            <EyeOff size={28} />
            <strong>Aucune camera affichee</strong>
            <span>Reviens dans Reglages puis Cameras pour en reactiver.</span>
          </div>
        )}
      </div>
    </section>
  );
}

function SettingsScreen({
  appVersion,
  autoUpdateChecks,
  cameras,
  hiddenCameraNames,
  message,
  page,
  session,
  updateResult,
  updateStatus,
  onCheckUpdates,
  onDisconnect,
  onRefresh,
  onSettingsPageChange,
  onToggleCameraVisibility,
  onToggleAutoUpdateChecks
}: {
  appVersion: string;
  autoUpdateChecks: boolean;
  cameras: CameraSummary[];
  hiddenCameraNames: string[];
  message: string;
  page: SettingsPage;
  session: StoredSession | null;
  updateResult: UpdateCheckResult | null;
  updateStatus: UpdateStatus;
  onCheckUpdates: () => void;
  onDisconnect: () => void;
  onRefresh: () => void;
  onSettingsPageChange: (page: SettingsPage) => void;
  onToggleCameraVisibility: (cameraName: string, visible: boolean) => void;
  onToggleAutoUpdateChecks: (enabled: boolean) => void;
}) {
  if (page === "account") {
    return <AccountSettings appVersion={appVersion} session={session} />;
  }

  if (page === "appearance") {
    return <AppearanceSettings />;
  }

  if (page === "permissions") {
    return <PermissionsSettings />;
  }

  if (page === "notifications") {
    return <NotificationsSettings />;
  }

  if (page === "faces") {
    return <FacesSettings />;
  }

  if (page === "cameras") {
    return (
      <CameraVisibilitySettings
        cameras={cameras}
        hiddenCameraNames={hiddenCameraNames}
        onToggleCameraVisibility={onToggleCameraVisibility}
      />
    );
  }

  return (
    <SettingsHome
      appVersion={appVersion}
      autoUpdateChecks={autoUpdateChecks}
      message={message}
      session={session}
      updateResult={updateResult}
      updateStatus={updateStatus}
      onCheckUpdates={onCheckUpdates}
      onDisconnect={onDisconnect}
      onRefresh={onRefresh}
      onSettingsPageChange={onSettingsPageChange}
      onToggleAutoUpdateChecks={onToggleAutoUpdateChecks}
    />
  );
}

function SettingsHome({
  appVersion,
  autoUpdateChecks,
  message,
  session,
  updateResult,
  updateStatus,
  onCheckUpdates,
  onDisconnect,
  onRefresh,
  onSettingsPageChange,
  onToggleAutoUpdateChecks
}: {
  appVersion: string;
  autoUpdateChecks: boolean;
  message: string;
  session: StoredSession | null;
  updateResult: UpdateCheckResult | null;
  updateStatus: UpdateStatus;
  onCheckUpdates: () => void;
  onDisconnect: () => void;
  onRefresh: () => void;
  onSettingsPageChange: (page: SettingsPage) => void;
  onToggleAutoUpdateChecks: (enabled: boolean) => void;
}) {
  const updateSummary = updateResult?.message ?? "Recherche de mise a jour";

  return (
    <section className="settings-panel settings-home" aria-label="Reglages">
      <div className="profile-hero">
        <img src={logoUrl} alt="FriCamGate logo" />
        <strong>{session?.username ?? "Utilisateur"}</strong>
        <span>{session?.role ?? "viewer"}</span>
      </div>

      <SettingsSection title="Manage">
        <SettingsRow icon={Bot} label="Assistant" detail="Automatisations et aide locale" />
        <SettingsRow icon={Activity} label="Sensors" detail="Etats et capteurs Frigate" />
        <SettingsRow icon={Video} label="Cameras" detail="Visibilite sur l'accueil" onClick={() => onSettingsPageChange("cameras")} />
        <SettingsRow icon={Map} label="Floor Plan" detail="Plan et zones cameras" />
        <SettingsRow icon={ScanFace} label="Faces" detail="Visages connus et inconnus" onClick={() => onSettingsPageChange("faces")} />
        <SettingsRow icon={BrainCircuit} label="Training" detail="Reconnaissance et apprentissage" />
        <SettingsRow icon={Puzzle} label="Plugins" detail="Extensions futures" />
        <SettingsRow icon={Workflow} label="Automations" detail="Regles et reactions" />
        <SettingsRow icon={ServerCog} label="Instances" detail={session?.baseUrl ?? "-"} />
      </SettingsSection>

      <SettingsSection title="System">
        <SettingsRow icon={UserCircle} label="Account" detail={session?.username ?? "-"} onClick={() => onSettingsPageChange("account")} />
        <SettingsRow icon={Palette} label="Appearance" detail="Theme, interface, navigation" onClick={() => onSettingsPageChange("appearance")} />
        <SettingsRow icon={BellRing} label="Notifications" detail="Alertes et heures silencieuses" onClick={() => onSettingsPageChange("notifications")} />
        <SettingsRow icon={ShieldCheck} label="Permissions" detail="Micro, localisation, notifications" onClick={() => onSettingsPageChange("permissions")} />
        <SettingsRow icon={PackageCheck} label="Updates" detail={updateSummary} onClick={onCheckUpdates} />
      </SettingsSection>

      <UpdateCard
        autoUpdateChecks={autoUpdateChecks}
        currentVersion={appVersion}
        result={updateResult}
        status={updateStatus}
        onCheckUpdates={onCheckUpdates}
        onToggleAutoUpdateChecks={onToggleAutoUpdateChecks}
      />

      <SettingsSection title="Actions">
        <SettingsRow icon={Info} label="About" detail={`${APP_NAME} ${appVersion}`} />
        <SettingsRow icon={RefreshCcw} label="Reload" detail={message} onClick={onRefresh} />
        <SettingsRow icon={Server} label="Switch server" detail={session?.baseUrl ?? "-"} onClick={onDisconnect} />
        <SettingsRow danger icon={Power} label="Logout" detail="Effacer la session locale" onClick={onDisconnect} />
      </SettingsSection>
    </section>
  );
}

function CameraVisibilitySettings({
  cameras,
  hiddenCameraNames,
  onToggleCameraVisibility
}: {
  cameras: CameraSummary[];
  hiddenCameraNames: string[];
  onToggleCameraVisibility: (cameraName: string, visible: boolean) => void;
}) {
  const visibleCount = cameras.filter((camera) => !hiddenCameraNames.includes(camera.name)).length;

  return (
    <section className="settings-panel settings-page" aria-label="Cameras">
      <SettingsSection title="Home">
        <div className="settings-card camera-visibility-card">
          <p className="lead-text">
            Choisis les cameras affichees sur l'accueil. Les flux restent disponibles dans Frigate; ce reglage ne masque que cette app.
          </p>
          <div className="camera-visibility-summary">
            <Video size={18} />
            <span>
              {visibleCount} / {cameras.length} affichees
            </span>
          </div>
          <div className="camera-visibility-list">
            {cameras.map((camera) => {
              const visible = !hiddenCameraNames.includes(camera.name);
              const Icon = visible ? Eye : EyeOff;

              return (
                <label className="camera-visibility-row" key={camera.name}>
                  <span className="camera-visibility-icon">
                    <Icon size={21} />
                  </span>
                  <span className="camera-visibility-text">
                    <strong>{camera.friendlyName}</strong>
                    <small>{camera.name}</small>
                  </span>
                  <input
                    aria-label={`Show ${camera.friendlyName}`}
                    checked={visible}
                    onChange={(event) => onToggleCameraVisibility(camera.name, event.target.checked)}
                    type="checkbox"
                  />
                  <span className="switch-control" aria-hidden="true" />
                </label>
              );
            })}
          </div>
        </div>
      </SettingsSection>
    </section>
  );
}

function AccountSettings({ appVersion, session }: { appVersion: string; session: StoredSession | null }) {
  return (
    <section className="settings-panel settings-page" aria-label="Account">
      <SettingsSection title="Profile">
        <div className="settings-card profile-card">
          <img src={logoUrl} alt="FriCamGate logo" className="profile-card-logo" />
          <TextField label="Username" defaultValue={session?.username ?? ""} />
          <button className="primary-button compact-action" type="button">
            Update profile
          </button>
        </div>
      </SettingsSection>

      <SettingsSection title="Password">
        <div className="settings-card form-card">
          <PasswordField label="New password" />
          <PasswordField label="Confirm new password" />
          <button className="primary-button compact-action" type="button">
            Change password
          </button>
        </div>
      </SettingsSection>

      <SettingsSection title="Two-Factor Authentication">
        <div className="settings-card text-card">
          <h3>Two-Factor Authentication</h3>
          <p>Add an extra layer of security to your account by enabling two-factor authentication.</p>
          <small>{APP_NAME} {appVersion}</small>
        </div>
      </SettingsSection>
    </section>
  );
}

function AppearanceSettings() {
  return (
    <section className="settings-panel settings-page" aria-label="Appearance">
      <SettingsSection title="Theme">
        <div className="settings-card form-card">
          <SelectField icon={Palette} label="Theme" value="dark" />
          <SwitchItem checked label="System preference" text="System setting for the user interface" />
        </div>
      </SettingsSection>

      <SettingsSection title="Locale">
        <div className="settings-card form-card">
          <SelectField icon={Languages} label="Language" value="System default" />
        </div>
      </SettingsSection>

      <SettingsSection title="Interface">
        <div className="settings-card form-card">
          <SelectField icon={PanelTop} label="Start page" value="Home" text="The page shown after login and when opening FriCamGate" />
          <SelectField icon={SlidersHorizontal} label="Default settings page" value="Account" text="The default page shown when opening settings" />
          <SelectField icon={Rows3} label="Table rows per page" value="15" text="How many rows a table shows per page" />
          <SwitchItem checked label="Keep navbar collapsed" text="Keeps the navbar collapsed, even on larger screens" />
        </div>
      </SettingsSection>

      <SettingsSection title="Navigation">
        <div className="settings-card stacked-card">
          <SwitchItem label="Settings pages in the navigation" text="Shows the settings pages as their own group in the sidebar." />
          <SwitchItem label="Main collapsible" text="The Main group can be collapsed in the sidebar." />
          <SwitchItem label="Manage collapsible" text="The Manage group can be collapsed in the sidebar." />
          <SwitchItem checked label="System collapsible" text="The System group can be collapsed in the sidebar." />
        </div>
      </SettingsSection>
    </section>
  );
}

function PermissionsSettings() {
  return (
    <section className="settings-panel settings-page" aria-label="Permissions">
      <SettingsSection title="App Permissions">
        <div className="settings-card permission-card">
          <p className="lead-text">What this device allows FriCamGate to use. These are system permissions of your device, not server settings.</p>
          <PermissionRow icon={Mic} label="Microphone" text="Needed for two-way audio." state="Not asked yet" action="Request" />
          <PermissionRow icon={MapPin} label="Location" text="Needed for location-based automations." state="Not asked yet" action="Request" />
          <PermissionRow icon={BellRing} label="Notifications" text="Needed to show notifications on this device." state="Allowed" />
        </div>
      </SettingsSection>
    </section>
  );
}

function NotificationsSettings() {
  return (
    <section className="settings-panel settings-page" aria-label="Notifications">
      <SettingsSection title="General">
        <div className="settings-card notification-card">
          <p className="lead-text">
            The master switch enables or disables every notification at once. Quiet Hours suppress external delivery during the chosen daily window.
          </p>
          <SwitchItem checked label="Enable Notifications" text="Turn this on to start receiving notifications for your account." />
          <InlineActionRow label="This device" text="This device is set up to receive notifications." action="Re-sync" />
          <SwitchItem label="Quiet Hours" text="Suppress non-critical notifications during a daily window." />
        </div>
      </SettingsSection>

      <SettingsSection title="System Notifications">
        <div className="settings-card notification-card">
          <p className="lead-text">
            Built-in events that FriCamGate emits: lifecycle and update notices. Each entry can be toggled individually.
          </p>
          <SwitchItem label="Plugin updates available" text="When an installed extension has a new release." />
          <SwitchItem checked label="Server became available" text="When your Frigate server reconnects." />
          <SwitchItem label="Camera went offline" text="When a camera stops responding." />
        </div>
      </SettingsSection>
    </section>
  );
}

function FacesSettings() {
  return (
    <section className="settings-panel settings-page faces-page" aria-label="Faces">
      <div className="faces-section-title">
        <span>Known Faces</span>
        <strong>0</strong>
      </div>
      <div className="faces-grid">
        <FaceCard name="Visage 1" images="0 images" />
        <FaceCard name="Visage 2" images="0 images" />
      </div>

      <div className="faces-section-title">
        <span>Unknown Faces</span>
        <strong>0</strong>
      </div>
      <p className="muted">No unknown faces detected.</p>

      <div className="faces-floating-actions">
        <button className="faces-reembed-button" type="button">
          <Database size={18} />
          Re-embed faces
        </button>
        <div className="faces-action-stack">
          <button className="floating-button" aria-label="Refresh faces" type="button">
            <RotateCw size={21} />
          </button>
          <button className="primary-fab" aria-label="Add face" type="button">
            <Plus size={30} />
          </button>
        </div>
      </div>
    </section>
  );
}

function SettingsSection({ children, title }: { children: ReactNode; title: string }) {
  return (
    <div className="settings-section">
      <p className="settings-group-title">{title}</p>
      {children}
    </div>
  );
}

function SettingsRow({
  danger = false,
  detail,
  icon: Icon,
  label,
  onClick
}: {
  danger?: boolean;
  detail: string;
  icon: typeof Home;
  label: string;
  onClick?: () => void;
}) {
  const className = danger ? "settings-row is-danger" : "settings-row";
  const content = (
    <>
      <span className="settings-row-icon">
        <Icon size={22} />
      </span>
      <span className="settings-row-text">
        <strong>{label}</strong>
        <small>{detail}</small>
      </span>
      <ChevronRight className="settings-row-chevron" size={21} />
    </>
  );

  if (onClick) {
    return (
      <button aria-label={`${label} ${detail}`} className={className} onClick={onClick} type="button">
        {content}
      </button>
    );
  }

  return <div className={className}>{content}</div>;
}

function TextField({ defaultValue = "", label }: { defaultValue?: string; label: string }) {
  return (
    <label className="settings-field">
      <span>{label}</span>
      <input defaultValue={defaultValue} />
    </label>
  );
}

function PasswordField({ label }: { label: string }) {
  return (
    <label className="settings-field password-field">
      <span>{label}</span>
      <span className="password-input-shell">
        <input type="password" />
        <Eye size={18} />
      </span>
    </label>
  );
}

function SelectField({
  icon: Icon,
  label,
  text,
  value
}: {
  icon: typeof Home;
  label: string;
  text?: string;
  value: string;
}) {
  return (
    <div className="settings-select">
      <span className="settings-select-label">{label}</span>
      <button className="settings-select-button" type="button">
        <Icon size={18} />
        <strong>{value}</strong>
        <ChevronDown size={22} />
      </button>
      {text ? <p>{text}</p> : null}
    </div>
  );
}

function SwitchItem({
  checked,
  label,
  text
}: {
  checked?: boolean;
  label: string;
  text: string;
}) {
  return (
    <label className="switch-item">
      <span>
        <strong>{label}</strong>
        <small>{text}</small>
      </span>
      <input type="checkbox" defaultChecked={checked} />
      <span className="switch-control" aria-hidden="true" />
    </label>
  );
}

function PermissionRow({
  action,
  icon: Icon,
  label,
  state,
  text
}: {
  action?: string;
  icon: typeof Home;
  label: string;
  state: string;
  text: string;
}) {
  const isAllowed = state === "Allowed";

  return (
    <div className="permission-row">
      <Icon size={20} />
      <div>
        <strong>{label}</strong>
        <small>{text}</small>
      </div>
      <span className={isAllowed ? "permission-badge is-allowed" : "permission-badge"}>{state}</span>
      {action ? (
        <button className="primary-button permission-action" type="button">
          {action}
        </button>
      ) : null}
    </div>
  );
}

function InlineActionRow({ action, label, text }: { action: string; label: string; text: string }) {
  return (
    <div className="inline-action-row">
      <span>
        <strong>{label}</strong>
        <small>{text}</small>
      </span>
      <button className="secondary-button inline-action" type="button">
        {action}
      </button>
    </div>
  );
}

function FaceCard({ images, name }: { images: string; name: string }) {
  return (
    <article className="face-card">
      <div className="face-image">
        <UserCircle size={58} />
      </div>
      <div>
        <strong>{name}</strong>
        <span>{images}</span>
      </div>
    </article>
  );
}

function UpdateCard({
  autoUpdateChecks,
  currentVersion,
  result,
  status,
  onCheckUpdates,
  onToggleAutoUpdateChecks
}: {
  autoUpdateChecks: boolean;
  currentVersion: string;
  result: UpdateCheckResult | null;
  status: UpdateStatus;
  onCheckUpdates: () => void;
  onToggleAutoUpdateChecks: (enabled: boolean) => void;
}) {
  const StatusIcon = getUpdateStatusIcon(status);
  const latestVersion = result?.latestVersion ?? "-";
  const message = status === "checking" ? "Recherche en cours..." : (result?.message ?? "Recherche automatique active.");

  return (
    <div className="settings-update-card" aria-live="polite">
      <div className={`update-status is-${status}`}>
        {status === "checking" ? <Loader2 className="spin" size={20} /> : <StatusIcon size={20} />}
        <div>
          <strong>{message}</strong>
          <small>
            Installee {currentVersion} - Derniere {latestVersion}
          </small>
        </div>
      </div>

      <label className="settings-toggle">
        <span>
          <strong>Recherche automatique</strong>
          <small>Au lancement de l'app</small>
        </span>
        <input
          type="checkbox"
          checked={autoUpdateChecks}
          onChange={(event) => onToggleAutoUpdateChecks(event.target.checked)}
        />
      </label>

      <div className="settings-actions">
        {status === "available" && result?.downloadUrl ? (
          <button className="primary-button" onClick={() => openReleases(result.downloadUrl)} type="button">
            <PackageCheck size={18} />
            Mettre a jour
          </button>
        ) : null}
        <button className="secondary-button" disabled={status === "checking"} onClick={onCheckUpdates} type="button">
          {status === "checking" ? <Loader2 className="spin" size={18} /> : <RefreshCcw size={18} />}
          Verifier
        </button>
        <button className="secondary-button" onClick={() => openReleases(result?.releaseUrl)} type="button">
          <ExternalLink size={18} />
          GitHub
        </button>
      </div>
    </div>
  );
}

function getUpdateStatusIcon(status: UpdateStatus) {
  if (status === "current") {
    return CheckCircle2;
  }

  if (status === "available") {
    return PackageCheck;
  }

  if (status === "error" || status === "unavailable") {
    return AlertCircle;
  }

  return PackageCheck;
}

function openReleases(url = UPDATE_RELEASES_URL) {
  window.open(url, "_blank", "noopener,noreferrer");
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

function CameraCard({ authToken, camera, api }: { authToken?: string; camera: CameraSummary; api: FrigateApi | null }) {
  const [refreshKey, setRefreshKey] = useState(0);
  const [liveFailed, setLiveFailed] = useState(false);
  const [liveLoaded, setLiveLoaded] = useState(false);
  const liveStreamUrl = camera.liveStreamUrl || api?.liveStreamUrl(camera.name) || "";
  const shouldLoadLive = Boolean(liveStreamUrl) && !liveFailed;
  const isLive = shouldLoadLive && liveLoaded;
  const LiveVideo = canUseNativeLive(authToken) ? MjpegLiveVideo : MseLiveVideo;

  const handleLiveFailed = useCallback(() => {
    setLiveLoaded(false);
    setLiveFailed(true);
  }, []);

  const handleLiveLoaded = useCallback(() => {
    setLiveLoaded(true);
  }, []);

  useEffect(() => {
    setLiveFailed(false);
    setLiveLoaded(false);
  }, [camera.name, liveStreamUrl]);

  function refreshCamera() {
    setLiveFailed(false);
    setLiveLoaded(false);
    setRefreshKey((key) => key + 1);
  }

  return (
    <article className="camera-card" data-testid="camera-card">
      {shouldLoadLive ? (
        <LiveVideo
          key={`${liveStreamUrl}:${refreshKey}`}
          className="camera-image"
          authToken={authToken}
          onError={handleLiveFailed}
          onPlaying={handleLiveLoaded}
          title={`Live video for ${camera.friendlyName}`}
          url={liveStreamUrl}
        />
      ) : (
        <div className="camera-placeholder" />
      )}
      <div className="camera-overlay">
        <span className="pill">{camera.friendlyName}</span>
        <span className={isLive ? "age-badge" : "age-badge is-fallback"}>{isLive ? "live" : "image"}</span>
      </div>
      <div className="quick-actions">
        <button className="floating-button" aria-label={`Refresh ${camera.friendlyName}`} onClick={refreshCamera}>
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
      fallbackUrl={event.previewUrl ? event.thumbnailUrl : undefined}
      fallback={<div className="event-thumb event-thumb-empty" />}
      url={event.previewUrl || event.thumbnailUrl}
    />
  );
}

function AuthenticatedImage({
  alt,
  api,
  authToken,
  className,
  fallbackUrl,
  fallback,
  onFailed,
  onLoaded,
  url
}: {
  alt: string;
  api: FrigateApi | null;
  authToken?: string;
  className: string;
  fallbackUrl?: string;
  fallback: ReactNode;
  onFailed?: () => void;
  onLoaded?: () => void;
  url?: string;
}) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [sourceUrl, setSourceUrl] = useState(url || fallbackUrl);

  useEffect(() => {
    setSourceUrl(url || fallbackUrl);
  }, [fallbackUrl, url]);

  useEffect(() => {
    let active = true;
    setDataUrl(null);
    setFailed(false);

    if (!api || !authToken || !sourceUrl) {
      setFailed(true);
      onFailed?.();
      return () => {
        active = false;
      };
    }

    if (sourceUrl.startsWith("data:")) {
      setDataUrl(sourceUrl);
      onLoaded?.();
      return () => {
        active = false;
      };
    }

    api
      .getImageDataUrl(authToken, sourceUrl)
      .then((nextDataUrl) => {
        if (active) {
          setDataUrl(nextDataUrl);
          onLoaded?.();
        }
      })
      .catch(() => {
        if (active) {
          if (fallbackUrl && sourceUrl !== fallbackUrl) {
            setSourceUrl(fallbackUrl);
            return;
          }
          setFailed(true);
          onFailed?.();
        }
      });

    return () => {
      active = false;
    };
  }, [api, authToken, fallbackUrl, onFailed, onLoaded, sourceUrl]);

  if (!dataUrl || failed) {
    return <>{fallback}</>;
  }

  return <img className={className} src={dataUrl} alt={alt} />;
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
