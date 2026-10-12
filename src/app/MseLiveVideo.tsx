import { useEffect, useRef } from "react";
import { canUseNativeLive, openNativeLiveStream, type NativeLiveConnection } from "../native/frigateLive";

const mseCodecs = [
  "avc1.42E01E",
  "avc1.4D401F",
  "avc1.4D4029",
  "avc1.64001F",
  "avc1.640029",
  "avc1.64002A",
  "avc1.640033",
  "hvc1.1.6.L153.B0",
  "mp4a.40.2",
  "mp4a.40.5",
  "flac",
  "opus"
];

type ManagedMediaSourceWindow = Window & {
  ManagedMediaSource?: typeof MediaSource;
};

const maxReconnectAttempts = 4;
const reconnectDelayMs = 1200;
const startupTimeoutMs = 5000;
const videoDimensionTimeoutMs = 900;
const bufferedWindowSeconds = 15;
const segmentBufferSize = 2 * 1024 * 1024;

export function MseLiveVideo({
  authToken,
  className,
  muted = true,
  onError,
  onPlaying,
  title,
  url
}: {
  authToken?: string;
  className: string;
  muted?: boolean;
  onError?: () => void;
  onPlaying?: () => void;
  title: string;
  url: string;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) {
      return;
    }

    video.muted = muted;
    if (!muted) {
      void video.play().catch(() => undefined);
    }
  }, [muted]);

  useEffect(() => {
    const video = videoRef.current;
    const MediaSourceClass = getMediaSourceConstructor();

    if (!video || !MediaSourceClass || !url) {
      onError?.();
      return undefined;
    }

    const CurrentMediaSource = MediaSourceClass;
    const currentVideo = video;
    let active = true;
    let connectionId = "";
    let mediaSource: MediaSource | null = null;
    let objectUrl = "";
    let sourceBuffer: SourceBuffer | null = null;
    let websocket: WebSocket | null = null;
    let nativeConnection: NativeLiveConnection | null = null;
    let videoDimensionTimeout: number | undefined;
    let startupTimeout: number | undefined;
    let reconnectTimeout: number | undefined;
    let reconnectAttempts = 0;
    let liveFrameLoaded = false;
    let bufferedSegmentLength = 0;
    let bufferedSegments = new Uint8Array(segmentBufferSize);
    const pendingNativeMessages: string[] = [];

    const cleanupConnection = () => {
      window.clearTimeout(startupTimeout);
      window.clearTimeout(reconnectTimeout);
      window.clearTimeout(videoDimensionTimeout);
      websocket?.close();
      nativeConnection?.close();
      websocket = null;
      nativeConnection = null;
      pendingNativeMessages.length = 0;

      if (sourceBuffer) {
        sourceBuffer.removeEventListener("updateend", handleUpdateEnd);
      }
      sourceBuffer = null;
      bufferedSegmentLength = 0;
      mediaSource = null;

      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
        objectUrl = "";
      }
    };

    const failLive = (fallback = false) => {
      if (!active) {
        return;
      }

      if (fallback || reconnectAttempts >= maxReconnectAttempts) {
        onError?.();
        return;
      }

      reconnectAttempts += 1;
      cleanupConnection();
      reconnectTimeout = window.setTimeout(startLive, reconnectDelayMs);
    };

    const trimBuffer = () => {
      if (!sourceBuffer?.buffered.length || !mediaSource) {
        return;
      }

      try {
        const removableEnd = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1) - bufferedWindowSeconds;
        const removableStart = sourceBuffer.buffered.start(0);
        if (removableEnd > removableStart) {
          sourceBuffer.remove(removableStart, removableEnd);
          mediaSource.setLiveSeekableRange?.(removableEnd, removableEnd + bufferedWindowSeconds);
        }
      } catch {
        // A short failed trim should not tear down an otherwise healthy live stream.
      }
    };

    const jumpToLiveEdge = () => {
      if (!sourceBuffer?.buffered.length) {
        return;
      }

      const rangeIndex = sourceBuffer.buffered.length - 1;
      const rangeStart = sourceBuffer.buffered.start(rangeIndex);
      const rangeEnd = sourceBuffer.buffered.end(rangeIndex);
      const targetTime = Math.max(rangeStart, rangeEnd - 0.75);
      if (currentVideo.currentTime < rangeStart || rangeEnd - currentVideo.currentTime > 3) {
        currentVideo.currentTime = targetTime;
        currentVideo.playbackRate = 1;
        void currentVideo.play().catch(() => undefined);
      }
    };

    function handleUpdateEnd() {
      if (!active || !sourceBuffer || sourceBuffer.updating) {
        return;
      }

      if (bufferedSegmentLength > 0) {
        const segment = bufferedSegments.slice(0, bufferedSegmentLength);
        bufferedSegmentLength = 0;
        try {
          sourceBuffer.appendBuffer(segment);
        } catch {
          failLive();
        }
        return;
      }

      trimBuffer();
      jumpToLiveEdge();
    }

    const appendSegment = (segment: ArrayBuffer) => {
      if (!active || !sourceBuffer) {
        return;
      }

      if (sourceBuffer.updating || bufferedSegmentLength > 0) {
        const bytes = new Uint8Array(segment);
        if (bytes.byteLength > bufferedSegments.byteLength - bufferedSegmentLength) {
          bufferedSegments = new Uint8Array(Math.max(segmentBufferSize, bufferedSegmentLength + bytes.byteLength));
        }
        bufferedSegments.set(bytes, bufferedSegmentLength);
        bufferedSegmentLength += bytes.byteLength;
        return;
      }

      try {
        sourceBuffer.appendBuffer(segment);
      } catch {
        failLive();
      }
    };

    const handleTextMessage = (data: string) => {
      try {
        const message = JSON.parse(data) as { type?: string; value?: string };
        if (!active) {
          return;
        }

        if (message.type === "error") {
          failLive(true);
          return;
        }

        if (message.type !== "mse" || !message.value || !mediaSource) {
          return;
        }

        window.clearTimeout(startupTimeout);
        sourceBuffer = mediaSource.addSourceBuffer(message.value);
        sourceBuffer.mode = "segments";
        sourceBuffer.addEventListener("updateend", handleUpdateEnd);
        void video.play().catch(() => undefined);
      } catch {
        failLive();
      }
    };

    const handleBinarySegment = (segment: ArrayBuffer) => {
      appendSegment(segment);
    };

    const handleMessage = (event: MessageEvent<string | ArrayBuffer | Blob>) => {
      if (typeof event.data === "string") {
        handleTextMessage(event.data);
        return;
      }

      if (event.data instanceof ArrayBuffer) {
        handleBinarySegment(event.data);
        return;
      }

      void event.data.arrayBuffer().then((buffer) => {
        if (active) {
          handleBinarySegment(buffer);
        }
      });
    };

    const sendMseRequest = (send: (message: string) => void) => {
      startupTimeout = window.setTimeout(failLive, startupTimeoutMs);
      send(
        JSON.stringify({
          type: "mse",
          value: getSupportedMseCodecs(MediaSourceClass)
        })
      );
    };

    const handleSourceOpen = () => {
      if (canUseNativeLive(authToken)) {
        void openNativeLiveStream({
          authToken: authToken ?? "",
          handlers: {
            onBinary: handleBinarySegment,
            onClose: () => {
              if (active && !sourceBuffer) {
                failLive();
              }
            },
            onError: failLive,
            onOpen: () =>
              sendMseRequest((message) => {
                if (nativeConnection) {
                  nativeConnection.send(message);
                  return;
                }
                pendingNativeMessages.push(message);
              }),
            onText: handleTextMessage
          },
          id: connectionId,
          url
        })
          .then((connection) => {
            if (!active) {
              connection.close();
              return;
            }
            nativeConnection = connection;
            while (pendingNativeMessages.length > 0) {
              const message = pendingNativeMessages.shift();
              if (message) {
                nativeConnection.send(message);
              }
            }
          })
          .catch(failLive);
        return;
      }

      try {
        websocket = new WebSocket(url);
        websocket.binaryType = "arraybuffer";
        websocket.addEventListener("open", () => sendMseRequest((message) => websocket?.send(message)));
        websocket.addEventListener("message", handleMessage);
        websocket.addEventListener("error", () => failLive());
        websocket.addEventListener("close", () => {
          if (active && !sourceBuffer) {
            failLive();
          }
        });
      } catch {
        failLive();
      }
    };

    function startLive() {
      if (!active) {
        return;
      }

      connectionId = `live-${Date.now()}-${Math.random().toString(36).slice(2)}`;
      mediaSource = new CurrentMediaSource();
      objectUrl = URL.createObjectURL(mediaSource);
      currentVideo.src = objectUrl;
      mediaSource.addEventListener("sourceopen", handleSourceOpen, { once: true });
      void currentVideo.play().catch(() => undefined);
    }

    const handleLoadedData = () => {
      window.clearTimeout(videoDimensionTimeout);
      videoDimensionTimeout = window.setTimeout(() => {
        if (!active) {
          return;
        }

        if (currentVideo.videoWidth > 0 || currentVideo.videoHeight > 0) {
          liveFrameLoaded = true;
          reconnectAttempts = 0;
          onPlaying?.();
          return;
        }

        failLive(true);
      }, videoDimensionTimeoutMs);
    };

    const handleVideoError = () => {
      if (!liveFrameLoaded) {
        failLive();
        return;
      }

      liveFrameLoaded = false;
      failLive();
    };

    const handleProgress = () => {
      if (!currentVideo.buffered.length) {
        return;
      }

      const rangeIndex = currentVideo.buffered.length - 1;
      const rangeStart = currentVideo.buffered.start(rangeIndex);
      const liveEdge = currentVideo.buffered.end(rangeIndex);
      const bufferedTime = liveEdge - currentVideo.currentTime;
      if (currentVideo.currentTime < rangeStart || bufferedTime > 3) {
        currentVideo.currentTime = Math.max(rangeStart, liveEdge - 0.75);
        currentVideo.playbackRate = 1;
        return;
      }

      if (bufferedTime > 1.5) {
        currentVideo.playbackRate = Math.min(1.5, 1 + bufferedTime / 10);
        return;
      }
      currentVideo.playbackRate = 1;
    };

    video.addEventListener("loadeddata", handleLoadedData);
    video.addEventListener("error", handleVideoError);
    video.addEventListener("progress", handleProgress);
    startLive();

    return () => {
      active = false;
      cleanupConnection();
      video.removeEventListener("loadeddata", handleLoadedData);
      video.removeEventListener("error", handleVideoError);
      video.removeEventListener("progress", handleProgress);
      video.removeAttribute("src");
      video.load();
    };
  }, [authToken, onError, onPlaying, url]);

  return <video ref={videoRef} aria-label={title} autoPlay className={className} muted={muted} playsInline preload="auto" />;
}

export function getSupportedMseCodecs(MediaSourceClass = getMediaSourceConstructor()): string {
  if (!MediaSourceClass) {
    return "";
  }

  return mseCodecs
    .filter((codec) => {
      return MediaSourceClass.isTypeSupported(`video/mp4; codecs="${codec}"`);
    })
    .join(",");
}

function getMediaSourceConstructor(): typeof MediaSource | null {
  const sourceWindow = window as ManagedMediaSourceWindow;
  return window.MediaSource ?? sourceWindow.ManagedMediaSource ?? null;
}
