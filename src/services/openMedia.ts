import { Capacitor } from "@capacitor/core";
import { YtDlp } from "@/plugins/yt-dlp";
import type { DownloadJob } from "./downloadEngine";

export async function openDownloadedFile(job: DownloadJob): Promise<void> {
  if (Capacitor.getPlatform() !== "android") {
    throw new Error("Open file is available on Android only.");
  }

  const target = job.openUri || job.filePath;
  if (!target) {
    throw new Error("File location is not available.");
  }

  await YtDlp.openMediaFile({
    openUri: job.openUri,
    filePath: job.filePath,
    mimeType: job.mimeType,
  });
}
