import { Capacitor } from "@capacitor/core";
import { extractUrlsFromText, isValidHttpUrl } from "./urlInput";

export type MediaKind = "video" | "audio";

export type ResolvedItem = {
  id: string;
  title: string;
  kind: MediaKind;
  estimatedBytes: number;
  sourceUrl: string;
};

export type ResolveResult =
  | { ok: true; batch: false; item: ResolvedItem }
  | { ok: true; batch: true; items: ResolvedItem[]; label: string }
  | { ok: false; reason: "invalid" | "unsupported" | "probeFailed" };

function hashId(seed: string): string {
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (h << 5) - h + seed.charCodeAt(i);
  return `job_${Math.abs(h).toString(36)}`;
}

function guessKind(url: string): MediaKind {
  const lower = url.toLowerCase();
  if (/\.(mp3|m4a|aac|flac|wav|ogg)(\?|$)/i.test(lower)) return "audio";
  return "video";
}

function estimateSize(kind: MediaKind): number {
  const base = kind === "audio" ? 8 * 1024 * 1024 : 120 * 1024 * 1024;
  return base * (0.4 + Math.random() * 0.6);
}

function titleFromUrl(url: string, index?: number): string {
  try {
    const path = new URL(url).pathname.split("/").filter(Boolean).pop();
    if (path && path.length > 2) return decodeURIComponent(path).slice(0, 48);
  } catch {
    /* ignore */
  }
  const suffix = index !== undefined ? ` #${index + 1}` : "";
  return `media${suffix}`;
}

function isPlaylistUrl(url: string): boolean {
  const u = url.toLowerCase();
  return (
    u.includes("list=") ||
    u.includes("/playlist") ||
    u.includes("/channel/") ||
    u.includes("/user/") ||
    (u.includes("/@") && u.includes("/videos")) ||
    u.includes("album") ||
    u.includes("/series/")
  );
}

function fallbackItem(url: string, index?: number): ResolvedItem {
  const kind = guessKind(url);
  return {
    id: hashId(index !== undefined ? `${url}_${index}` : url),
    title: titleFromUrl(url, index),
    kind,
    estimatedBytes: estimateSize(kind),
    sourceUrl: url,
  };
}

async function probeOne(url: string, flatPlaylist: boolean): Promise<ResolvedItem[] | null> {
  const { probeUrlNative } = await import("./nativeYtDlp");
  return probeUrlNative(url, flatPlaylist);
}

async function resolveSingleUrl(url: string): Promise<ResolvedItem | null> {
  if (!isValidHttpUrl(url)) return null;

  const probed = await probeOne(url, false);
  if (probed?.length === 1) return probed[0];
  if (probed && probed.length > 1) return probed[0];

  if (Capacitor.getPlatform() === "android") {
    const probedLoose = await probeOne(url, true);
    if (probedLoose?.length) return probedLoose[0];
  }

  return fallbackItem(url);
}

async function resolvePlaylistUrl(url: string): Promise<ResolvedItem[] | null> {
  if (!isValidHttpUrl(url)) return null;

  const probed = await probeOne(url, true);
  if (probed && probed.length > 0) return probed;

  if (Capacitor.getPlatform() === "android") return null;

  return null;
}

async function resolveMultipleUrls(urls: string[]): Promise<ResolvedItem[]> {
  const items: ResolvedItem[] = [];
  const seen = new Set<string>();

  for (let i = 0; i < urls.length; i++) {
    const url = urls[i];
    if (!isValidHttpUrl(url) || seen.has(url)) continue;
    seen.add(url);

    const item = await resolveSingleUrl(url);
    if (item) {
      items.push({
        ...item,
        id: hashId(`${item.sourceUrl}_${items.length}`),
        title: item.title || titleFromUrl(url, items.length),
      });
    }
  }

  return items;
}

export async function resolveMediaUrl(
  raw: string,
  forceBatch?: boolean,
): Promise<ResolveResult> {
  const urls = extractUrlsFromText(raw);
  if (urls.length === 0) return { ok: false, reason: "invalid" };

  if (urls.length > 1) {
    const items = await resolveMultipleUrls(urls);
    if (items.length === 0) {
      return {
        ok: false,
        reason: Capacitor.getPlatform() === "android" ? "probeFailed" : "invalid",
      };
    }
    return {
      ok: true,
      batch: true,
      items,
      label: `${items.length} links`,
    };
  }

  const url = urls[0];
  const wantPlaylist = forceBatch === true || (forceBatch !== false && isPlaylistUrl(url));

  if (wantPlaylist) {
    const items = await resolvePlaylistUrl(url);
    if (!items || items.length === 0) {
      return { ok: false, reason: "probeFailed" };
    }
    if (items.length === 1) {
      return { ok: true, batch: false, item: items[0] };
    }
    return {
      ok: true,
      batch: true,
      items,
      label: `${items.length} items`,
    };
  }

  const item = await resolveSingleUrl(url);
  if (!item) return { ok: false, reason: "invalid" };
  return { ok: true, batch: false, item };
}
