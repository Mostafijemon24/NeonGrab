import { Capacitor } from "@capacitor/core";
import { YtDlp, type YtDlpAvailability, type YtDlpQuality } from "@/plugins/yt-dlp";

export type { YtDlpAvailability };
import type { MediaKind, ResolvedItem } from "./urlResolver";

let cachedAvailability: YtDlpAvailability | null = null;
let ensurePromise: Promise<boolean> | null = null;

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

export async function ensureYtDlpBinary(): Promise<boolean> {
  if (Capacitor.getPlatform() !== "android") return false;
  if (ensurePromise) return ensurePromise;

  ensurePromise = (async () => {
    const status = await getYtDlpAvailability(true);
    if (status.available) return true;

    try {
      const engine = await YtDlp.ensureEngine();
      if (engine.ok) {
        cachedAvailability = await YtDlp.isAvailable();
        return cachedAvailability.available;
      }
    } catch {
      /* ignore */
    }

    try {
      const assets = await YtDlp.installBinaryFromAssets();
      if (assets.ok) {
        cachedAvailability = await YtDlp.isAvailable();
        return cachedAvailability.available;
      }
    } catch {
      /* ignore */
    }

    cachedAvailability = await getYtDlpAvailability(true);
    return cachedAvailability.available;
  })();

  try {
    return await ensurePromise;
  } finally {
    ensurePromise = null;
  }
}

export function qualityToNative(quality: string): YtDlpQuality {
  if (quality === "1080" || quality === "720" || quality === "480" || quality === "audio") {
    return quality;
  }
  return "auto";
}

export async function probeUrlNative(
  url: string,
  flatPlaylist: boolean,
): Promise<ResolvedItem[] | null> {
  const ready = await ensureYtDlpBinary();
  if (!ready) return null;

  try {
    const result = await YtDlp.probe({ url, flatPlaylist });
    if (!result.ok || result.entries.length === 0) return null;

    return result.entries.map((e) => ({
      id: e.id,
      title: e.title,
      kind: e.kind as MediaKind,
      estimatedBytes: e.filesize ?? estimateFromDuration(e.duration, e.kind),
      sourceUrl: e.url,
    }));
  } catch {
    return null;
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
