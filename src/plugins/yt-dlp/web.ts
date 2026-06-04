import { WebPlugin } from "@capacitor/core";
import type {
  DownloadFolderInfo,
  YtDlpAvailability,
  YtDlpDownloadOptions,
  YtDlpPlugin,
  YtDlpProbeResult,
} from "./definitions";

export class YtDlpWeb extends WebPlugin implements YtDlpPlugin {
  async isAvailable(): Promise<YtDlpAvailability> {
    return {
      available: false,
      message: "yt-dlp runs on Android only. Web uses simulated downloads.",
    };
  }

  async installBinaryFromAssets(): Promise<{ ok: boolean; message?: string }> {
    return { ok: false, message: "Not supported on web." };
  }

  async ensureEngine(): Promise<{ ok: boolean; message?: string }> {
    return { ok: false, message: "Android only" };
  }

  async getDownloadFolder(): Promise<DownloadFolderInfo> {
    return {
      configured: false,
      displayName: "Browser preview (pick folder on Android)",
    };
  }

  async pickDownloadFolder(): Promise<DownloadFolderInfo> {
    throw this.unavailable("Folder picker is available on Android only.");
  }

  async probe(): Promise<YtDlpProbeResult> {
    return { ok: false, batch: false, entries: [], message: "Native probe unavailable on web." };
  }

  async extractViaWebView(): Promise<{ streamUrl: string; title: string }> {
    throw this.unavailable("WebView extraction is available on Android only.");
  }

  async download(_options: YtDlpDownloadOptions): Promise<{ ok: boolean; message?: string }> {
    return { ok: false, message: "Native download unavailable on web." };
  }

  async pause(): Promise<void> {
    /* no-op */
  }

  async resume(): Promise<void> {
    /* no-op */
  }

  async cancel(): Promise<void> {
    /* no-op */
  }

  async openMediaFile(): Promise<void> {
    throw this.unavailable("Open file is available on Android only.");
  }
}
