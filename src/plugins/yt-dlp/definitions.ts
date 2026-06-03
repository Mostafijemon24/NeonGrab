export type YtDlpQuality = "auto" | "1080" | "720" | "480" | "audio";

export type YtDlpAvailability = {
  available: boolean;
  initializing?: boolean;
  packInstalled?: boolean;
  version?: string;
  binaryPath?: string;
  message?: string;
};

export type DownloadFolderInfo = {
  configured: boolean;
  displayName: string;
  uri?: string;
};

export type YtDlpProbeEntry = {
  id: string;
  title: string;
  url: string;
  duration?: number;
  filesize?: number;
  kind: "video" | "audio";
};

export type YtDlpProbeResult = {
  ok: boolean;
  batch: boolean;
  entries: YtDlpProbeEntry[];
  message?: string;
};

export type YtDlpDownloadOptions = {
  jobId: string;
  url: string;
  title: string;
  quality: YtDlpQuality;
  kind: "video" | "audio";
  maxThreads?: number;
};

export type YtDlpProgressEvent = {
  jobId: string;
  progress: number;
  downloadedBytes: number;
  totalBytes: number;
  speedBps: number;
};

export type YtDlpCompleteEvent = {
  jobId: string;
  filePath: string;
  openUri?: string;
  mimeType?: string;
  title: string;
  totalBytes: number;
};

export type YtDlpFailedEvent = {
  jobId: string;
  message: string;
};

export type EngineSetupProgressEvent = {
  progress: number;
  message: string;
};

export type EngineSetupFailedEvent = {
  message: string;
};

export interface YtDlpPlugin {
  isAvailable(): Promise<YtDlpAvailability>;
  installBinaryFromAssets(): Promise<{ ok: boolean; message?: string }>;
  ensureEngine(options?: {
    retry?: boolean;
  }): Promise<{ ok: boolean; message?: string; version?: string }>;
  getDownloadFolder(): Promise<DownloadFolderInfo>;
  pickDownloadFolder(): Promise<DownloadFolderInfo>;
  probe(options: { url: string; flatPlaylist?: boolean }): Promise<YtDlpProbeResult>;
  download(options: YtDlpDownloadOptions): Promise<{ ok: boolean; message?: string }>;
  pause(options: { jobId: string }): Promise<void>;
  resume(options: { jobId: string }): Promise<void>;
  cancel(options: { jobId: string }): Promise<void>;
  openMediaFile(options: {
    openUri?: string;
    filePath?: string;
    mimeType?: string;
  }): Promise<void>;
  addListener(
    eventName: "downloadProgress",
    listener: (e: YtDlpProgressEvent) => void,
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: "downloadComplete",
    listener: (e: YtDlpCompleteEvent) => void,
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: "downloadFailed",
    listener: (e: YtDlpFailedEvent) => void,
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: "engineSetupProgress",
    listener: (e: EngineSetupProgressEvent) => void,
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: "engineSetupComplete",
    listener: () => void,
  ): Promise<{ remove: () => void }>;
  addListener(
    eventName: "engineSetupFailed",
    listener: (e: EngineSetupFailedEvent) => void,
  ): Promise<{ remove: () => void }>;
}
