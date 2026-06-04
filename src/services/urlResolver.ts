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
  | { ok: false; reason: "invalid" | "unsupported" | "probeFailed"; detail?: string };

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

/** Normalize URL: fix YouTube hosts, strip tracking params (utm_*, si, fbclid, etc.). */
export function normalizeMediaUrl(url: string): string {
  try {
    const u = new URL(url.trim());
    const host = u.hostname.toLowerCase();

    // Normalize YouTube
    if (host === "youtube.com" || host === "m.youtube.com" || host === "youtu.be") {
      if (host === "youtu.be") {
        const id = u.pathname.replace(/^\//, "");
        if (id) {
          const clean = new URL(`https://www.youtube.com/watch`);
          clean.searchParams.set("v", id);
          return clean.toString();
        }
      } else {
        u.hostname = "www.youtube.com";
      }
    }

    // Strip tracking params for all sites
    const TRACKING = ["si", "feature", "fbclid", "ref", "referer", "referrer"];
    for (const key of [...u.searchParams.keys()]) {
      if (key.startsWith("utm_") || TRACKING.includes(key)) {
        u.searchParams.delete(key);
      }
    }
    return u.toString();
  } catch {
    return url.trim();
  }
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

async function probeOne(
  url: string,
  flatPlaylist: boolean,
): Promise<{ items: ResolvedItem[] | null; error?: string }> {
  const { probeUrlNative } = await import("./nativeYtDlp");
  return probeUrlNative(normalizeMediaUrl(url), flatPlaylist);
}

async function resolveSingleUrl(
  url: string,
): Promise<{ item: ResolvedItem | null; probeError?: string }> {
  if (!isValidHttpUrl(url)) return { item: null };

  const probed = await probeOne(url, false);
  if (probed.items?.length === 1) return { item: probed.items[0] };
  if (probed.items && probed.items.length > 1) return { item: probed.items[0] };

  if (Capacitor.getPlatform() === "android") {
    const probedLoose = await probeOne(url, true);
    if (probedLoose.items?.length) return { item: probedLoose.items[0] };
    /* Do not queue blind downloads — probe must succeed so yt-dlp can extract the URL */
    return {
      item: null,
      probeError: probedLoose.error ?? probed.error ?? undefined,
    };
  }

  return { item: fallbackItem(url) };
}

async function resolvePlaylistUrl(
  url: string,
): Promise<{ items: ResolvedItem[] | null; detail?: string }> {
  if (!isValidHttpUrl(url)) return { items: null };

  const probed = await probeOne(url, true);
  if (probed.items && probed.items.length > 0) {
    return { items: probed.items };
  }

  return { items: null, detail: probed.error };
}

async function resolveMultipleUrls(urls: string[]): Promise<ResolvedItem[]> {
  const items: ResolvedItem[] = [];
  const seen = new Set<string>();

  for (let i = 0; i < urls.length; i++) {
    const url = urls[i];
    if (!isValidHttpUrl(url) || seen.has(url)) continue;
    seen.add(url);

    const resolved = await resolveSingleUrl(url);
    if (resolved.item) {
      items.push({
        ...resolved.item,
        id: hashId(`${resolved.item.sourceUrl}_${items.length}`),
        title: resolved.item.title || titleFromUrl(url, items.length),
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
    const playlist = await resolvePlaylistUrl(url);
    if (!playlist.items || playlist.items.length === 0) {
      return { ok: false, reason: "probeFailed", detail: playlist.detail };
    }
    if (playlist.items.length === 1) {
      return { ok: true, batch: false, item: playlist.items[0] };
    }
    return {
      ok: true,
      batch: true,
      items: playlist.items,
      label: `${playlist.items.length} items`,
    };
  }

  const resolved = await resolveSingleUrl(url);
  if (!resolved.item) {
    if (Capacitor.getPlatform() === "android" && resolved.probeError) {
      return { ok: false, reason: "probeFailed", detail: resolved.probeError };
    }
    return { ok: false, reason: "invalid" };
  }
  return { ok: true, batch: false, item: resolved.item };
}
