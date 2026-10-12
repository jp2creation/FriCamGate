import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";

interface FrigateLivePlugin {
  addListener(eventName: "open" | "text" | "binary" | "error" | "close", listenerFunc: (event: FrigateLiveEvent) => void): Promise<PluginListenerHandle>;
  close(options: { id: string }): Promise<void>;
  connect(options: { authToken: string; id: string; url: string }): Promise<void>;
  send(options: { id: string; message: string }): Promise<void>;
}

interface FrigateLiveEvent {
  code?: number;
  data?: string;
  id?: string;
  message?: string;
  reason?: string;
  status?: number;
}

export interface NativeLiveConnection {
  close: () => void;
  send: (message: string) => void;
}

export interface NativeLiveHandlers {
  onBinary: (segment: ArrayBuffer) => void;
  onClose: () => void;
  onError: () => void;
  onOpen: () => void;
  onText: (message: string) => void;
}

const FrigateLive = registerPlugin<FrigateLivePlugin>("FrigateLive");

export function canUseNativeLive(authToken?: string): boolean {
  return Boolean(authToken) && Capacitor.getPlatform() === "android" && Capacitor.isPluginAvailable("FrigateLive");
}

export async function openNativeLiveStream({
  authToken,
  handlers,
  id,
  url
}: {
  authToken: string;
  handlers: NativeLiveHandlers;
  id: string;
  url: string;
}): Promise<NativeLiveConnection> {
  const listenerHandles = await Promise.all([
    FrigateLive.addListener("open", (event) => {
      if (event.id === id) {
        handlers.onOpen();
      }
    }),
    FrigateLive.addListener("text", (event) => {
      if (event.id === id && event.data) {
        handlers.onText(event.data);
      }
    }),
    FrigateLive.addListener("binary", (event) => {
      if (event.id === id && event.data) {
        handlers.onBinary(base64ToArrayBuffer(event.data));
      }
    }),
    FrigateLive.addListener("error", (event) => {
      if (event.id === id) {
        handlers.onError();
      }
    }),
    FrigateLive.addListener("close", (event) => {
      if (event.id === id) {
        handlers.onClose();
      }
    })
  ]);

  try {
    await FrigateLive.connect({ authToken, id, url });
  } catch (error) {
    await Promise.all(listenerHandles.map((handle) => handle.remove()));
    throw error;
  }

  return {
    close: () => {
      void FrigateLive.close({ id }).catch(() => undefined);
      void Promise.all(listenerHandles.map((handle) => handle.remove())).catch(() => undefined);
    },
    send: (message: string) => {
      void FrigateLive.send({ id, message }).catch(() => handlers.onError());
    }
  };
}

function base64ToArrayBuffer(value: string): ArrayBuffer {
  const binary = window.atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes.buffer;
}
