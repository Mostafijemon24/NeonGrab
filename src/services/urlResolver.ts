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
  | { ok: false; reason: "invalid" | "unsupported" };

const URL_PATTERN =
  /^https?:\/\/[\w.-]+(?:\.[\w.-]+)+[\w\-._~:/?#[\]@!$&'()*+,;=%.]*$/i;

function isValidUrl(raw: string): boolean {
  try {
    const u = new URL(raw.trim());
    return (u.protocol === "http:" || u.protocol === "https:") && URL_PATTERN.test(raw.trim());
  } catch {
    return false;
  }
}

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

function estimateSize(kind: MediaKind, batch = false): number {
  const base = kind === "audio" ? 8 * 1024 * 1024 : 120 * 1024 * 1024;
  return batch ? base : base * (0.4 + Math.random() * 0.6);
}

function titleFromUrl(url: string, index?: number): string {
  try {
    const path = new URL(url).pathname.split("/").filter(Boolean).pop();
    if (path && path.length > 2) return decodeURIComponent(path).slice(0, 48);
  } catch {
    /* ignore */
  }
  const suffix = index !== undefined ? `_${String(index + 1).padStart(2, "0")}` : "";
  return `media${suffix}.mp4`;
}

/** Detect playlist-style URLs without exposing platform names in UI */
function isPlaylistUrl(url: string): boolean {
  const u = url.toLowerCase();
  return (
    u.includes("list=") ||
    u.includes("/playlist") ||
    u.includes("/channel/") ||
    u.includes("/user/") ||
    u.includes("/@") && u.includes("/videos") ||
    u.includes("album") ||
    u.includes("/series/")
  );
}

function expandPlaylist(url: string): ResolvedItem[] {
  const count = Math.min(24, 6 + Math.floor((url.length % 12) + 4));
  return Array.from({ length: count }, (_, i) => {
    const id = hashId(`${url}_${i}`);
    const kind = guessKind(url);
    return {
      id,
      title: titleFromUrl(url, i),
      kind,
      estimatedBytes: estimateSize(kind, true) / count,
      sourceUrl: url,
    };
  });
}

export async function resolveMediaUrl(
  raw: string,
  forceBatch?: boolean,
): Promise<ResolveResult> {
  const url = raw.trim();
  if (!isValidUrl(url)) return { ok: false, reason: "invalid" };

  const batch = forceBatch ?? isPlaylistUrl(url);

  const { probeUrlNative } = await import("./nativeYtDlp");
  const nativeItems = await probeUrlNative(url, batch);
  if (nativeItems && nativeItems.length > 0) {
    if (nativeItems.length === 1) {
      return { ok: true, batch: false, item: nativeItems[0] };
    }
    return {
      ok: true,
      batch: true,
      items: nativeItems,
      label: `${nativeItems.length} items`,
    };
  }

  await new Promise((r) => setTimeout(r, 200));

  const kind = guessKind(url);

  if (batch) {
    const items = expandPlaylist(url);
    return {
      ok: true,
      batch: true,
      items,
      label: `${items.length} items`,
    };
  }

  return {
    ok: true,
    batch: false,
    item: {
      id: hashId(url),
      title: titleFromUrl(url),
      kind,
      estimatedBytes: estimateSize(kind),
      sourceUrl: url,
    },
  };
}
