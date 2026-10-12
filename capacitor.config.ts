import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "fr.degert.fricamgate",
  appName: "FriCamGate",
  webDir: "dist",
  server: {
    androidScheme: "http"
  }
};

export default config;
