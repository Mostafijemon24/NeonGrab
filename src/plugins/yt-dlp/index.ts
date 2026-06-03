import { registerPlugin } from "@capacitor/core";
import type { YtDlpPlugin } from "./definitions";

export const YtDlp = registerPlugin<YtDlpPlugin>("YtDlp", {
  web: () => import("./web").then((m) => new m.YtDlpWeb()),
});

export type {
  DownloadFolderInfo,
  YtDlpAvailability,
  YtDlpCompleteEvent,
  YtDlpDownloadOptions,
  YtDlpFailedEvent,
  YtDlpPlugin,
  YtDlpProbeEntry,
  YtDlpProbeResult,
  YtDlpProgressEvent,
  YtDlpQuality,
} from "./definitions";
