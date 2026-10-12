import { useEffect, useState } from "react";
import { openNativeLiveStream, type NativeLiveConnection } from "../native/frigateLive";

const startupTimeoutMs = 8000;
const reconnectDelayMs = 1200;
const maxReconnectAttempts = 4;
const minimumFrameIntervalMs = 110;

export function MjpegLiveVideo({
  authToken,
  className,
  onError,
  onPlaying,
  title,
  url
}: {
  authToken?: string;
  className: string;
  onError?: () => void;
  onPlaying?: () => void;
  title: string;
  url: string;
}) {
  const [frameUrl, setFrameUrl] = useState("");

  useEffect(() => {
    const streamUrl = toMjpegUrl(url);

    if (!authToken || !streamUrl) {
      onError?.();
      return undefined;
    }

    const liveAuthToken = authToken;
    let active = true;
    let connection: NativeLiveConnection | null = null;
    let reconnectAttempts = 0;
    let reconnectTimeout: number | undefined;
    let startupTimeout: number | undefined;
    let displayedFrameUrl = "";
    let hasPlayed = false;
    let lastFrameAt = 0;
    let pendingMessages: string[] = [];
    let pendingFrameUrl = "";

    const revokeDisplayedFrame = () => {
      if (displayedFrameUrl) {
        URL.revokeObjectURL(displayedFrameUrl);
        displayedFrameUrl = "";
      }
    };

    const revokePendingFrame = () => {
      if (pendingFrameUrl) {
        URL.revokeObjectURL(pendingFrameUrl);
        pendingFrameUrl = "";
      }
    };

    const closeConnection = () => {
      window.clearTimeout(startupTimeout);
      connection?.close();
      connection = null;
      pendingMessages = [];
    };

    const failLive = () => {
      if (!active) {
        return;
      }

      closeConnection();
      if (reconnectAttempts >= maxReconnectAttempts) {
        onError?.();
        return;
      }

      reconnectAttempts += 1;
      reconnectTimeout = window.setTimeout(startStream, reconnectDelayMs);
    };

    const sendMessage = (message: string) => {
      if (connection) {
        connection.send(message);
        return;
      }

      pendingMessages.push(message);
    };

    const handleText = (data: string) => {
      try {
        const message = JSON.parse(data) as { type?: string };
        if (message.type === "error") {
          failLive();
        }
      } catch {
        failLive();
      }
    };

    const handleBinary = (segment: ArrayBuffer) => {
      if (!active || !isJpeg(segment)) {
        return;
      }

      const now = Date.now();
      if (now - lastFrameAt < minimumFrameIntervalMs) {
        return;
      }

      lastFrameAt = now;
      const nextFrameUrl = URL.createObjectURL(new Blob([segment], { type: "image/jpeg" }));
      const nextFrame = new window.Image();

      revokePendingFrame();
      pendingFrameUrl = nextFrameUrl;
      nextFrame.onload = () => {
        if (!active || pendingFrameUrl !== nextFrameUrl) {
          URL.revokeObjectURL(nextFrameUrl);
          return;
        }

        pendingFrameUrl = "";
        revokeDisplayedFrame();
        displayedFrameUrl = nextFrameUrl;
        setFrameUrl(nextFrameUrl);

        if (!hasPlayed) {
          hasPlayed = true;
          reconnectAttempts = 0;
          window.clearTimeout(startupTimeout);
          onPlaying?.();
        }
      };
      nextFrame.onerror = () => {
        if (pendingFrameUrl === nextFrameUrl) {
          pendingFrameUrl = "";
        }
        URL.revokeObjectURL(nextFrameUrl);
      };
      nextFrame.src = nextFrameUrl;
    };

    function startStream() {
      closeConnection();
      const connectionId = `mjpeg-${Date.now()}-${Math.random().toString(36).slice(2)}`;
      startupTimeout = window.setTimeout(failLive, startupTimeoutMs);

      void openNativeLiveStream({
        authToken: liveAuthToken,
        handlers: {
          onBinary: handleBinary,
          onClose: failLive,
          onError: failLive,
          onOpen: () => {
            sendMessage(JSON.stringify({ type: "mjpeg" }));
          },
          onText: handleText
        },
        id: connectionId,
        url: streamUrl
      })
        .then((nextConnection) => {
          if (!active) {
            nextConnection.close();
            return;
          }

          connection = nextConnection;
          pendingMessages.forEach((message) => connection?.send(message));
          pendingMessages = [];
        })
        .catch(failLive);
    }

    startStream();

    return () => {
      active = false;
      window.clearTimeout(reconnectTimeout);
      closeConnection();
      revokePendingFrame();
      revokeDisplayedFrame();
      setFrameUrl("");
    };
  }, [authToken, onError, onPlaying, url]);

  if (!frameUrl) {
    return <div aria-label={title} className={`${className} camera-placeholder`} role="img" />;
  }

  return <img aria-label={title} alt={title} className={className} src={frameUrl} />;
}

function toMjpegUrl(url: string): string {
  try {
    const liveUrl = new URL(url);
    const source = liveUrl.searchParams.get("src");
    if (!source) {
      return "";
    }

    const mjpegSource = source.startsWith("ffmpeg:")
      ? source
      : `ffmpeg:${source}#video=mjpeg#width=640`;

    liveUrl.searchParams.set("src", mjpegSource.includes("#video=mjpeg") ? mjpegSource : `${mjpegSource}#video=mjpeg#width=640`);
    return liveUrl.toString();
  } catch {
    return "";
  }
}

function isJpeg(segment: ArrayBuffer): boolean {
  const bytes = new Uint8Array(segment, 0, Math.min(segment.byteLength, 2));
  return bytes.length === 2 && bytes[0] === 0xff && bytes[1] === 0xd8;
}
