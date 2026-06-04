import { Capacitor } from "@capacitor/core";
import { YtDlp, type YtDlpAvailability, type YtDlpQuality } from "@/plugins/yt-dlp";

export type { YtDlpAvailability };

export type EngineSetupProgressEvent = {
  progress: number;
  message: string;
};

export type EngineSetupFailedEvent = {
  message: string;
};

import type { MediaKind, ResolvedItem } from "./urlResolver";

let cachedAvailability: YtDlpAvailability | null = null;
let setupInFlight: Promise<YtDlpAvailability> | null = null;

export async function getYtDlpAvailability(
  forceRefresh = false,
): Promise<YtDlpAvailability> {
  if (!forceRefresh && cachedAvailability) return cachedAvailability;
  if (Capacitor.getPlatform() !== "android") {
    cachedAvailability = { available: false, message: "Not Android" };
    return cachedAvailability;
  }
  try {
    cachedAvailability = await YtDlp.isAvailable();
    return cachedAvailability;
  } catch {
    cachedAvailability = { available: false, message: "Plugin unavailable" };
    return cachedAvailability;
  }
}

export function subscribeEngineSetupEvents(handlers: {
  onProgress: (e: EngineSetupProgressEvent) => void;
  onComplete: () => void;
  onFailed: (e: EngineSetupFailedEvent) => void;
}): () => void {
  if (Capacitor.getPlatform() !== "android") {
    return () => {};
  }

  const removers: Array<() => void> = [];
  let cancelled = false;

  void (async () => {
    const [p, c, f] = await Promise.all([
      YtDlp.addListener("engineSetupProgress", (e) => {
        if (!cancelled) handlers.onProgress(e);
      }),
      YtDlp.addListener("engineSetupComplete", () => {
        if (!cancelled) handlers.onComplete();
      }),
      YtDlp.addListener("engineSetupFailed", (e) => {
        if (!cancelled) handlers.onFailed(e);
      }),
    ]);
    if (cancelled) {
      p.remove();
      c.remove();
      f.remove();
      return;
    }
    removers.push(() => p.remove(), () => c.remove(), () => f.remove());
  })();

  return () => {
    cancelled = true;
    removers.forEach((r) => r());
  };
}

export async function setupDownloadEngine(retry = false): Promise<YtDlpAvailability> {
  if (Capacitor.getPlatform() !== "android") {
    return { available: false, message: "Not Android" };
  }
  if (setupInFlight && !retry) {
    return setupInFlight;
  }

  const run = async (): Promise<YtDlpAvailability> => {
    cachedAvailability = null;
    try {
      const result = await YtDlp.ensureEngine({ retry });
      if (result.ok) {
        cachedAvailability = {
          available: true,
          version: result.version ?? "ready",
        };
        return cachedAvailability;
      }
      const status = await getYtDlpAvailability(true);
      cachedAvailability = {
        ...status,
        available: false,
        message: result.message ?? status.message ?? "Engine setup failed",
      };
      return cachedAvailability;
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Engine setup failed";
      cachedAvailability = { available: false, message: msg };
      return cachedAvailability;
    }
  };

  setupInFlight = run();
  try {
    return await setupInFlight;
  } finally {
    setupInFlight = null;
  }
}

export async function ensureYtDlpBinary(): Promise<boolean> {
  if (Capacitor.getPlatform() !== "android") return false;

  const status = await getYtDlpAvailability(true);
  if (status.available) return true;

  if (setupInFlight) {
    const after = await setupInFlight;
    return after.available;
  }

  const after = await setupDownloadEngine(false);
  return after.available;
}

export function qualityToNative(quality: string): YtDlpQuality {
  if (quality === "1080" || quality === "720" || quality === "480" || quality === "audio") {
    return quality;
  }
  return "auto";
}

export type ProbeUrlResult = {
  items: ResolvedItem[] | null;
  error?: string;
};

/** Capacitor Android reject(msg, code) — message is human text, code is e.g. PROBE_FAILED. */
function pluginErrorMessage(e: unknown): string {
  if (!e || typeof e !== "object") return "Probe failed";
  const err = e as { message?: string; code?: string };
  let message = err.message?.trim() ?? "";
  if (message.endsWith(" null")) {
    message = message.slice(0, -5).trim();
  }
  const code = err.code?.trim() ?? "";
  const looksLikeCode = (s: string) => /^[A-Z][A-Z0-9_]*$/.test(s);
  if (message && !looksLikeCode(message)) {
    const lower = message.toLowerCase();
    if (lower.includes("keyerror") || lower.includes("extractor error")) {
      return "Could not read link — open Settings, tap Retry engine on Wi‑Fi, use a direct video URL without referral text.";
    }
    return message;
  }
  if (code && !looksLikeCode(code)) return code;
  if (message) return message;
  return "Probe failed";
}

export async function extractUrlViaWebViewNative(url: string): Promise<{ streamUrl: string; title: string }> {
  const ready = await ensureYtDlpBinary();
  if (!ready) {
    throw new Error("Download engine is not ready");
  }
  return YtDlp.extractViaWebView({ url });
}

export async function probeUrlNative(
  url: string,
  flatPlaylist: boolean,
): Promise<ProbeUrlResult> {
  const ready = await ensureYtDlpBinary();
  if (!ready) {
    return { items: null, error: "Download engine is not ready" };
  }

  try {
    const result = await YtDlp.probe({ url, flatPlaylist });
    if (!result.ok || result.entries.length === 0) {
      return {
        items: null,
        error: result.message ?? "Playlist is empty or could not be read",
      };
    }

    return {
      items: result.entries.map((e) => ({
        id: e.id,
        title: e.title,
        kind: e.kind as MediaKind,
        estimatedBytes: e.filesize ?? estimateFromDuration(e.duration, e.kind),
        sourceUrl: e.url,
      })),
    };
  } catch (e) {
    return { items: null, error: pluginErrorMessage(e) };
  }
}

function estimateFromDuration(durationSec: number | undefined, kind: MediaKind): number {
  if (!durationSec) return kind === "audio" ? 8 * 1024 * 1024 : 80 * 1024 * 1024;
  const bitrate = kind === "audio" ? 192_000 : 2_500_000;
  return Math.max(512 * 1024, Math.floor((durationSec * bitrate) / 8));
}

export function subscribeNativeDownloadEvents(handlers: {
  onProgress: (e: import("@/plugins/yt-dlp").YtDlpProgressEvent) => void;
  onComplete: (e: import("@/plugins/yt-dlp").YtDlpCompleteEvent) => void;
  onFailed: (e: import("@/plugins/yt-dlp").YtDlpFailedEvent) => void;
}): () => void {
  const removers: Array<() => void> = [];
  let cancelled = false;

  void (async () => {
    const [p, c, f] = await Promise.all([
      YtDlp.addListener("downloadProgress", (e) => {
        if (!cancelled) handlers.onProgress(e);
      }),
      YtDlp.addListener("downloadComplete", (e) => {
        if (!cancelled) handlers.onComplete(e);
      }),
      YtDlp.addListener("downloadFailed", (e) => {
        if (!cancelled) handlers.onFailed(e);
      }),
    ]);
    if (cancelled) {
      p.remove();
      c.remove();
      f.remove();
      return;
    }
    removers.push(() => p.remove(), () => c.remove(), () => f.remove());
  })();

  return () => {
    cancelled = true;
    removers.forEach((r) => r());
  };
}
