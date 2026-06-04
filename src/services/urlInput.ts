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
  let t = token
    .replace(/^[\s"'[(]+/, "")
    .replace(/[\s"'),.;!?]+$/, "")
    .trim();
  /* Share links often append ?utm= / ?referral= — strip before probe */
  const cut = t.search(/[?&](?:utm[\w-]*|um|fbclid|referral|campaign|source|medium)=/i);
  if (cut > 0) t = t.slice(0, cut);
  return t;
}

export function isValidHttpUrl(raw: string): boolean {
  try {
    const u = new URL(raw.trim());
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}
