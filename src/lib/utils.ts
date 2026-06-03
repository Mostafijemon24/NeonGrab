import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  if (bytes < 1024 * 1024 * 1024)
    return `${(bytes / (1024 * 1024)).toFixed(0)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

export function formatSpeed(bytesPerSec: number): string {
  return `${(bytesPerSec / (1024 * 1024)).toFixed(1)}`;
}

/** Folder portion of a display path like `IDM/video.mp4`. */
export function folderFromDisplayPath(path: string): string {
  const slash = path.lastIndexOf("/");
  return slash > 0 ? path.slice(0, slash) : path;
}

export function shortenText(text: string, max = 48): string {
  if (text.length <= max) return text;
  return `${text.slice(0, max - 1)}…`;
}

export function filenameFromDisplayPath(path: string): string {
  const slash = path.lastIndexOf("/");
  return slash >= 0 ? path.slice(slash + 1) : path;
}

export function titleFromFilename(filename: string): string {
  const base = filename.replace(/\.[^.]+$/, "");
  try {
    return decodeURIComponent(base).replace(/_/g, " ").trim();
  } catch {
    return base.replace(/_/g, " ").trim();
  }
}

const YT_ID_RE = /^[a-zA-Z0-9_-]{11}$/;

export function looksLikeWeakTitle(title: string): boolean {
  const t = title.trim();
  if (!t) return true;
  if (t === "watch" || t === "shorts" || /^media(\s+#\d+)?$/i.test(t)) return true;
  if (YT_ID_RE.test(t)) return true;
  return false;
}

export function displayJobTitle(job: {
  title: string;
  filePath?: string;
}): string {
  if (!looksLikeWeakTitle(job.title)) return job.title;
  if (job.filePath) {
    const fromFile = titleFromFilename(filenameFromDisplayPath(job.filePath));
    if (fromFile && !looksLikeWeakTitle(fromFile)) return fromFile;
  }
  return job.title.trim() || "Download";
}
