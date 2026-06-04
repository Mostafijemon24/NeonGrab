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

const ADULT_HOST_RE =
  /xhamster|pornhub|xvideos|xnxx|redtube|youporn|spankbang|tube8|tnaflix|hclips/i;

export function isAdultMediaUrl(url: string): boolean {
  return ADULT_HOST_RE.test(url);
}

function isExtractorProbeError(err?: string): boolean {
  if (!err) return false;
  const e = err.toLowerCase();
  return (
    e.includes("extractor error") ||
    e.includes("keyerror") ||
    e.includes("videomodel") ||
    e.includes("playlist probe failed")
  );
}

/** Normalize URL: fix YouTube hosts, strip tracking params (utm_*, si, fbclid, etc.). */
export function normalizeMediaUrl(url: string): string {
  try {
    const u = new URL(url.trim());
    const host = u.hostname.toLowerCase();

    if (host.includes("xhamster")) {
      /* Keep regional host (xhamster1.desi, etc.) — only strip tracking query */
      if (u.pathname.includes("/videos/")) {
        u.search = "";
        u.hash = "";
      }
    } else if (host.includes("pornhub")) {
      u.hostname = "www.pornhub.com";
      if (u.pathname.includes("/view_video") || u.pathname.includes("/video")) {
        u.search = "";
        u.hash = "";
      }
    }

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
    const TRACKING = [
      "si",
      "feature",
      "fbclid",
      "ref",
      "referer",
      "referrer",
      "referral",
      "campaign",
      "source",
      "medium",
    ];
    for (const key of [...u.searchParams.keys()]) {
      const lower = key.toLowerCase();
      if (lower.startsWith("utm") || TRACKING.includes(lower)) {
        u.searchParams.delete(key);
      }
    }
    return u.toString();
  } catch {
    return url.trim();
  }
}

export function isDirectVideoPage(url: string): boolean {
  try {
    const path = new URL(url.trim()).pathname.toLowerCase();
    if (/\/videos\/[^/]+/.test(path)) return true;
    if (path.includes("/view_video")) return true;
    if (path.includes("/watch") && !path.includes("/watchlist")) return true;
    if (path.includes("/embed/")) return true;
    return false;
  } catch {
    return false;
  }
}

function isPlaylistUrl(url: string): boolean {
  if (isDirectVideoPage(url)) return false;
  try {
    const u = new URL(url.trim());
    const path = u.pathname.toLowerCase();
    const qs = u.search.toLowerCase();
    if (qs.includes("list=")) return true;
    if (path.includes("/playlist")) return true;
    if (path.includes("/channel/")) return true;
    if (path.includes("/user/") && !path.includes("/videos/")) return true;
    if (path.includes("/@") && path.includes("/videos")) return true;
    if (path.includes("/series/")) return true;
    if (/\/album(\/|$)/.test(path)) return true;
    return false;
  } catch {
    return false;
  }
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

  const normalized = normalizeMediaUrl(url);

  /* xHamster/PornHub: probe often fails due to bot protection. Try WebView interceptor first. */
  if (
    Capacitor.getPlatform() === "android" &&
    isAdultMediaUrl(normalized) &&
    isDirectVideoPage(normalized)
  ) {
    try {
      const { extractUrlViaWebViewNative } = await import("./nativeYtDlp");
      const extracted = await extractUrlViaWebViewNative(normalized);
      if (extracted && extracted.streamUrl) {
        return {
          item: {
            id: hashId(normalized),
            title: extracted.title || titleFromUrl(normalized),
            kind: "video",
            estimatedBytes: estimateSize("video"),
            sourceUrl: extracted.streamUrl, // Use the direct M3U8/MP4 stream
          },
        };
      }
    } catch (e) {
      console.warn("WebView extraction fallback failed", e);
    }
    return { item: fallbackItem(normalized) };
  }

  const probed = await probeOne(normalized, false);
  if (probed.items?.length === 1) return { item: probed.items[0] };
  if (probed.items && probed.items.length > 1) return { item: probed.items[0] };

  if (Capacitor.getPlatform() === "android") {
    const probedLoose = await probeOne(normalized, true);
    if (probedLoose.items?.length) return { item: probedLoose.items[0] };
    const probeErr = probedLoose.error ?? probed.error;
    if (isAdultMediaUrl(normalized) && isExtractorProbeError(probeErr)) {
      return { item: fallbackItem(normalized) };
    }
    if (isDirectVideoPage(normalized) && isExtractorProbeError(probeErr)) {
      return { item: fallbackItem(normalized) };
    }
    return {
      item: null,
      probeError: probeErr ?? undefined,
    };
  }

  return { item: fallbackItem(normalized) };
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
  const normalized = normalizeMediaUrl(url);
  const wantPlaylist =
    forceBatch === true ||
    (forceBatch !== false && isPlaylistUrl(normalized));

  if (wantPlaylist) {
    const playlist = await resolvePlaylistUrl(normalized);
    if (!playlist.items || playlist.items.length === 0) {
      if (
        Capacitor.getPlatform() === "android" &&
        isDirectVideoPage(normalized)
      ) {
        return { ok: true, batch: false, item: fallbackItem(normalized) };
      }
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
