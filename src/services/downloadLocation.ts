import { Capacitor } from "@capacitor/core";
import { YtDlp, type DownloadFolderInfo } from "@/plugins/yt-dlp";

export async function getDownloadFolder(): Promise<DownloadFolderInfo> {
  if (Capacitor.getPlatform() !== "android") {
    return {
      configured: false,
      displayName: "Android device required for folder picker",
    };
  }
  return YtDlp.getDownloadFolder();
}

export async function pickDownloadFolder(): Promise<DownloadFolderInfo> {
  if (Capacitor.getPlatform() !== "android") {
    throw new Error("Folder picker is available on Android only.");
  }
  return YtDlp.pickDownloadFolder();
}

export function formatFolderLabel(folder: DownloadFolderInfo): string {
  if (folder.configured && folder.displayName) return folder.displayName;
  return "";
}
