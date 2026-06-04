const URL_IN_TEXT =
  /https?:\/\/[^\s<>"']+/gi;

/** Pull every http(s) link from pasted text (newlines, labels, extra words). */
export function extractUrlsFromText(raw: string): string[] {
  const trimmed = raw.trim().replace(/\uFEFF/g, "");
  if (!trimmed) return [];

  const found = trimmed.match(URL_IN_TEXT);
  if (found?.length) {
    return [...new Set(found.map(cleanUrlToken))].filter(Boolean) as string[];
  }

  const single = cleanUrlToken(trimmed.split(/\s+/)[0] ?? "");
  return single ? [single] : [];
}

function cleanUrlToken(token: string): string {
  return token
    .replace(/^[\s"'[(]+/, "")
    .replace(/[\s"'),.;!?]+$/, "")
    .trim();
}

export function isValidHttpUrl(raw: string): boolean {
  try {
    const u = new URL(raw.trim());
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}
