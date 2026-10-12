import { useEffect, useRef } from "react";
import { openNativeLiveStream, type NativeLiveConnection } from "../native/frigateLive";

const startupTimeoutMs = 8000;

export function WebRtcLiveVideo({
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
  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    const video = videoRef.current;
    const signalingUrl = toWebRtcUrl(url);

    if (!video || !authToken || !signalingUrl || typeof RTCPeerConnection === "undefined") {
      onError?.();
      return undefined;
    }

    let active = true;
    let signaling: NativeLiveConnection | null = null;
    const pendingMessages: string[] = [];
    const peerConnection = new RTCPeerConnection({ bundlePolicy: "max-bundle" });
    const remoteStream = new MediaStream();
    const connectionId = `webrtc-${Date.now()}-${Math.random().toString(36).slice(2)}`;

    video.srcObject = remoteStream;

    const failLive = () => {
      if (active) {
        onError?.();
      }
    };

    const sendSignal = (message: object) => {
      const serialized = JSON.stringify(message);
      if (signaling) {
        signaling.send(serialized);
        return;
      }
      pendingMessages.push(serialized);
    };

    const startOffer = async () => {
      try {
        const offer = await peerConnection.createOffer();
        await peerConnection.setLocalDescription(offer);
        sendSignal({
          type: "webrtc/offer",
          value: peerConnection.localDescription?.sdp
        });
      } catch {
        failLive();
      }
    };

    peerConnection.addTransceiver("video", { direction: "recvonly" });
    peerConnection.addTransceiver("audio", { direction: "recvonly" });
    peerConnection.addEventListener("icecandidate", (event) => {
      if (!event.candidate) {
        return;
      }

      sendSignal({
        type: "webrtc/candidate",
        value: event.candidate.candidate
      });
    });
    peerConnection.addEventListener("track", (event) => {
      remoteStream.addTrack(event.track);
      void video.play().catch(() => undefined);
    });
    peerConnection.addEventListener("connectionstatechange", () => {
      if (["failed", "closed", "disconnected"].includes(peerConnection.connectionState)) {
        failLive();
      }
    });

    const handleSignal = (data: string) => {
      try {
        const message = JSON.parse(data) as { type?: string; value?: string };
        if (!active) {
          return;
        }

        if (message.type === "webrtc/answer" && message.value) {
          void peerConnection.setRemoteDescription({ type: "answer", sdp: message.value }).catch(failLive);
          return;
        }

        if (message.type === "webrtc/candidate" && message.value) {
          void peerConnection.addIceCandidate({ candidate: message.value, sdpMid: "0" }).catch(() => undefined);
          return;
        }

        if (message.type === "error") {
          failLive();
        }
      } catch {
        failLive();
      }
    };

    const startupTimeout = window.setTimeout(failLive, startupTimeoutMs);
    void openNativeLiveStream({
      authToken,
      handlers: {
        onBinary: () => undefined,
        onClose: failLive,
        onError: failLive,
        onOpen: () => {
          void startOffer();
        },
        onText: handleSignal
      },
      id: connectionId,
      url: signalingUrl
    })
      .then((connection) => {
        if (!active) {
          connection.close();
          return;
        }

        signaling = connection;
        while (pendingMessages.length > 0) {
          const message = pendingMessages.shift();
          if (message) {
            signaling.send(message);
          }
        }
      })
      .catch(failLive);

    const handleLoadedData = () => {
      window.clearTimeout(startupTimeout);
      onPlaying?.();
    };

    video.addEventListener("loadeddata", handleLoadedData);

    return () => {
      active = false;
      window.clearTimeout(startupTimeout);
      video.removeEventListener("loadeddata", handleLoadedData);
      signaling?.close();
      peerConnection.close();
      remoteStream.getTracks().forEach((track) => track.stop());
      video.srcObject = null;
    };
  }, [authToken, onError, onPlaying, url]);

  return <video ref={videoRef} aria-label={title} autoPlay className={className} muted playsInline preload="auto" />;
}

function toWebRtcUrl(url: string): string {
  return url.replace("/live/mse/api/ws", "/live/webrtc/api/ws");
}
