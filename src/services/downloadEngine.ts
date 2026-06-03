import { Capacitor } from "@capacitor/core";
import { YtDlp } from "@/plugins/yt-dlp";
import type { ResolvedItem } from "./urlResolver";
import {
  ensureYtDlpBinary,
  getYtDlpAvailability,
  qualityToNative,
  subscribeNativeDownloadEvents,
} from "./nativeYtDlp";

export type DownloadStatus =
  | "queued"
  | "downloading"
  | "paused"
  | "completed"
  | "failed";

export type DownloadJob = {
  id: string;
  title: string;
  kind: "video" | "audio";
  totalBytes: number;
  downloadedBytes: number;
  progress: number;
  segments: number;
  status: DownloadStatus;
  speedBps: number;
  sourceUrl: string;
  createdAt: number;
  completedAt?: number;
  filePath?: string;
  native?: boolean;
};

export type EngineCallbacks = {
  onUpdate: (job: DownloadJob) => void;
  onComplete: (job: DownloadJob) => void;
};

type ActiveWorker = {
  jobId: string;
  timer: ReturnType<typeof setInterval>;
};

type EngineOptions = {
  getMaxConcurrent: () => number;
  getMaxThreads: () => number;
  thermalGuard: () => boolean;
  getQuality: () => string;
};

function segmentCount(bytes: number, maxThreads: number): number {
  const ideal = Math.ceil(bytes / (8 * 1024 * 1024));
  return Math.max(2, Math.min(maxThreads * 2, ideal, 12));
}

export class DownloadEngine {
  private workers = new Map<string, ActiveWorker>();
  private jobs = new Map<string, DownloadJob>();
  private running = 0;
  private nativeReady = false;
  private nativeListenersAttached = false;
  private unsubscribeNative: (() => void) | null = null;

  constructor(
    private callbacks: EngineCallbacks,
    private options: EngineOptions,
  ) {
    void this.initNative();
  }

  private async initNative(): Promise<void> {
    if (Capacitor.getPlatform() !== "android") return;
    await ensureYtDlpBinary();
    const status = await getYtDlpAvailability(true);
    this.nativeReady = status.available;
    if (this.nativeReady) this.attachNativeListeners();
  }

  private attachNativeListeners(): void {
    if (this.nativeListenersAttached) return;
    this.nativeListenersAttached = true;
    this.unsubscribeNative = subscribeNativeDownloadEvents({
      onProgress: (e) => {
        const job = this.jobs.get(e.jobId);
        if (!job) return;
        job.status = "downloading";
        job.progress = e.progress;
        job.downloadedBytes = e.downloadedBytes;
        job.totalBytes = e.totalBytes || job.totalBytes;
        job.speedBps = e.speedBps;
        this.callbacks.onUpdate({ ...job });
      },
      onComplete: (e) => {
        const job = this.jobs.get(e.jobId);
        if (!job) return;
        job.status = "completed";
        job.progress = 100;
        job.downloadedBytes = e.totalBytes;
        job.totalBytes = e.totalBytes;
        job.filePath = e.filePath;
        job.title = e.title || job.title;
        job.completedAt = Date.now();
        job.speedBps = 0;
        this.running = Math.max(0, this.running - 1);
        this.callbacks.onUpdate({ ...job });
        this.callbacks.onComplete({ ...job });
        this.pump();
      },
      onFailed: (e) => {
        const job = this.jobs.get(e.jobId);
        if (!job) return;
        job.status = "failed";
        job.speedBps = 0;
        this.running = Math.max(0, this.running - 1);
        this.callbacks.onUpdate({ ...job });
        this.pump();
      },
    });
  }

  dispose(): void {
    this.unsubscribeNative?.();
    for (const w of this.workers.values()) clearInterval(w.timer);
    this.workers.clear();
  }

  getJob(id: string): DownloadJob | undefined {
    return this.jobs.get(id);
  }

  getAllJobs(): DownloadJob[] {
    return [...this.jobs.values()].sort((a, b) => b.createdAt - a.createdAt);
  }

  enqueue(item: ResolvedItem): DownloadJob {
    const existing = this.jobs.get(item.id);
    if (existing && existing.status !== "failed") return existing;

    const maxThreads = this.options.getMaxThreads();
    const job: DownloadJob = {
      id: item.id,
      title: item.title,
      kind: item.kind,
      totalBytes: item.estimatedBytes,
      downloadedBytes: 0,
      progress: 0,
      segments: segmentCount(item.estimatedBytes, maxThreads),
      status: "queued",
      speedBps: 0,
      sourceUrl: item.sourceUrl,
      createdAt: Date.now(),
      native: this.nativeReady,
    };
    this.jobs.set(item.id, job);
    this.callbacks.onUpdate(job);
    this.pump();
    return job;
  }

  pause(id: string): void {
    const job = this.jobs.get(id);
    if (job?.native) {
      void YtDlp.pause({ jobId: id });
      job.status = "paused";
      this.running = Math.max(0, this.running - 1);
      this.callbacks.onUpdate(job);
      this.pump();
      return;
    }
    const w = this.workers.get(id);
    if (w) {
      clearInterval(w.timer);
      this.workers.delete(id);
      this.running = Math.max(0, this.running - 1);
    }
    if (job && job.status === "downloading") {
      job.status = "paused";
      this.callbacks.onUpdate(job);
    }
    this.pump();
  }

  resume(id: string): void {
    const job = this.jobs.get(id);
    if (!job || job.status !== "paused") return;
    job.status = "queued";
    this.callbacks.onUpdate(job);
    if (job.native) {
      void YtDlp.download(this.nativeDownloadOptions(job));
      job.status = "downloading";
      this.running += 1;
      this.callbacks.onUpdate(job);
      return;
    }
    this.pump();
  }

  cancel(id: string): void {
    const job = this.jobs.get(id);
    if (job?.native) void YtDlp.cancel({ jobId: id });
    this.pause(id);
    this.jobs.delete(id);
  }

  clearCompleted(): void {
    for (const [id, job] of this.jobs) {
      if (job.status === "completed" || job.status === "failed") {
        this.jobs.delete(id);
      }
    }
  }

  private effectiveConcurrency(): number {
    let max = this.options.getMaxConcurrent();
    if (this.options.thermalGuard()) max = Math.max(1, Math.floor(max / 2));
    return max;
  }

  private pump(): void {
    const limit = this.effectiveConcurrency();
    const queued = this.getAllJobs().filter((j) => j.status === "queued");
    for (const job of queued) {
      if (this.running >= limit) break;
      if (job.native) {
        void this.startNative(job);
      } else {
        this.startSimulated(job);
      }
    }
  }

  private nativeDownloadOptions(job: DownloadJob) {
    return {
      jobId: job.id,
      url: job.sourceUrl,
      title: job.title,
      quality: qualityToNative(this.options.getQuality()),
      kind: job.kind,
      maxThreads: this.options.getMaxThreads(),
    };
  }

  private async startNative(job: DownloadJob): Promise<void> {
    if (job.status !== "queued") return;
    if (!this.nativeReady) {
      await this.initNative();
      job.native = this.nativeReady;
      if (!this.nativeReady) {
        this.startSimulated(job);
        return;
      }
      this.attachNativeListeners();
    }

    job.status = "downloading";
    this.running += 1;
    this.callbacks.onUpdate({ ...job });

    try {
      const res = await YtDlp.download(this.nativeDownloadOptions(job));
      if (!res.ok) {
        job.status = "failed";
        this.running = Math.max(0, this.running - 1);
        this.callbacks.onUpdate({ ...job });
        this.pump();
      }
    } catch {
      job.status = "failed";
      this.running = Math.max(0, this.running - 1);
      this.callbacks.onUpdate({ ...job });
      this.pump();
    }
  }

  private startSimulated(job: DownloadJob): void {
    if (this.workers.has(job.id)) return;
    job.status = "downloading";
    job.native = false;
    this.running += 1;
    this.callbacks.onUpdate({ ...job });

    const thermal = this.options.thermalGuard();
    const tickMs = thermal ? 120 : 80;
    const boost = thermal ? 0.55 : 1;

    const timer = setInterval(() => {
      const j = this.jobs.get(job.id);
      if (!j || j.status !== "downloading") {
        clearInterval(timer);
        this.workers.delete(job.id);
        this.running = Math.max(0, this.running - 1);
        this.pump();
        return;
      }

      const chunk =
        (j.totalBytes / j.segments) * (0.35 + Math.random() * 0.5) * boost;
      j.downloadedBytes = Math.min(j.totalBytes, j.downloadedBytes + chunk);
      j.progress = Math.round((j.downloadedBytes / j.totalBytes) * 100);
      j.speedBps = chunk / (tickMs / 1000);

      if (j.progress >= 100) {
        j.progress = 100;
        j.downloadedBytes = j.totalBytes;
        j.status = "completed";
        j.completedAt = Date.now();
        j.speedBps = 0;
        clearInterval(timer);
        this.workers.delete(job.id);
        this.running = Math.max(0, this.running - 1);
        this.callbacks.onUpdate({ ...j });
        this.callbacks.onComplete({ ...j });
        this.pump();
        return;
      }

      this.callbacks.onUpdate({ ...j });
    }, tickMs);

    this.workers.set(job.id, { jobId: job.id, timer });
  }
}
