import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.neongrab.downloader",
  appName: "NeonGrab",
  webDir: "dist",
  android: {
    allowMixedContent: true,
  },
  server: {
    androidScheme: "https",
  },
};

export default config;
