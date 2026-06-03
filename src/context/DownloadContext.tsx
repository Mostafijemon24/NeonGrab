import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  DownloadEngine,
  type DownloadJob,
} from "@/services/downloadEngine";
import { Capacitor } from "@capacitor/core";
import { getDownloadFolder } from "@/services/downloadLocation";
import { ensureYtDlpBinary } from "@/services/nativeYtDlp";
import { resolveMediaUrl } from "@/services/urlResolver";
import { extractUrlsFromText } from "@/services/urlInput";
import { useSettings } from "./SettingsContext";

type DownloadContextValue = {
  jobs: DownloadJob[];
  activeJobs: DownloadJob[];
  completedJobs: DownloadJob[];
  aggregateSpeedBps: number;
  activeThreadCount: number;
  thermalActive: boolean;
  enqueueUrl: (
    url: string,
    batchMode: boolean,
  ) => Promise<{ ok: boolean; count: number; started: number; error?: string }>;
  pause: (id: string) => void;
  resume: (id: string) => void;
  cancel: (id: string) => void;
  clearCompleted: () => void;
};

const DownloadContext = createContext<DownloadContextValue | null>(null);

export function DownloadProvider({ children }: { children: ReactNode }) {
  const {
    maxConcurrent,
    maxThreads,
    thermalGuard,
    wifiOnly,
    quality,
  } = useSettings();
  const [jobs, setJobs] = useState<DownloadJob[]>([]);
  const engineRef = useRef<DownloadEngine | null>(null);
  const optionsRef = useRef({
    getMaxConcurrent: () => maxConcurrent,
    getMaxThreads: () => maxThreads,
    thermalGuard: () => thermalGuard,
    getQuality: () => quality,
  });

  optionsRef.current = {
    getMaxConcurrent: () => maxConcurrent,
    getMaxThreads: () => maxThreads,
    thermalGuard: () => thermalGuard,
    getQuality: () => quality,
  };

  const refresh = useCallback(() => {
    setJobs(engineRef.current?.getAllJobs() ?? []);
  }, []);

  useEffect(() => {
    const engine = new DownloadEngine(
      {
        onUpdate: () => refresh(),
        onComplete: () => refresh(),
      },
      {
        getMaxConcurrent: () => optionsRef.current.getMaxConcurrent(),
        getMaxThreads: () => optionsRef.current.getMaxThreads(),
        thermalGuard: () => optionsRef.current.thermalGuard(),
        getQuality: () => optionsRef.current.getQuality(),
      },
    );
    engineRef.current = engine;
    refresh();
    return () => {
      engine.dispose();
      engineRef.current = null;
    };
  }, [refresh]);

  const enqueueUrl = useCallback(
    async (raw: string, batchMode: boolean) => {
      const urls = extractUrlsFromText(raw);
      if (urls.length === 0) {
        return { ok: false, count: 0, started: 0, error: "invalidUrl" };
      }

      if (wifiOnly && typeof navigator !== "undefined") {
        const conn = (navigator as Navigator & { connection?: { type?: string } })
          .connection;
        if (conn?.type === "cellular") {
          return { ok: false, count: 0, started: 0, error: "wifiOnlyBlocked" };
        }
      }

      if (Capacitor.getPlatform() === "android") {
        const folder = await getDownloadFolder();
        if (!folder.configured) {
          return { ok: false, count: 0, started: 0, error: "folderRequired" };
        }
        const ready = await ensureYtDlpBinary();
        if (!ready) {
          return { ok: false, count: 0, started: 0, error: "engineNotReady" };
        }
        await engineRef.current?.prepareNative();
      }

      const result = await resolveMediaUrl(raw, batchMode);
      if (!result.ok) {
        const err =
          result.reason === "probeFailed" ? "probeFailed" : "invalidUrl";
        return { ok: false, count: 0, started: 0, error: err };
      }

      const engine = engineRef.current;
      if (!engine) return { ok: false, count: 0, started: 0 };

      const items = result.batch ? result.items : [result.item];
      let started = 0;
      for (const item of items) {
        const job = engine.enqueue(item);
        if (job.status === "queued" || job.status === "downloading") started += 1;
      }
      refresh();

      return { ok: true, count: items.length, started };
    },
    [wifiOnly, refresh],
  );

  const activeJobs = useMemo(
    () =>
      jobs.filter(
        (j) =>
          j.status === "downloading" ||
          j.status === "queued" ||
          j.status === "paused" ||
          j.status === "failed",
      ),
    [jobs],
  );

  const completedJobs = useMemo(
    () => jobs.filter((j) => j.status === "completed"),
    [jobs],
  );

  const aggregateSpeedBps = useMemo(
    () =>
      activeJobs
        .filter((j) => j.status === "downloading")
        .reduce((s, j) => s + j.speedBps, 0),
    [activeJobs],
  );

  const activeThreadCount = useMemo(() => {
    const downloading = activeJobs.filter((j) => j.status === "downloading").length;
    const cap = thermalGuard ? Math.max(1, Math.floor(maxThreads / 2)) : maxThreads;
    return Math.min(cap, downloading * 2 || 0);
  }, [activeJobs, maxThreads, thermalGuard]);

  const value = useMemo<DownloadContextValue>(
    () => ({
      jobs,
      activeJobs,
      completedJobs,
      aggregateSpeedBps,
      activeThreadCount,
      thermalActive: thermalGuard && activeJobs.some((j) => j.status === "downloading"),
      enqueueUrl,
      pause: (id) => {
        engineRef.current?.pause(id);
        refresh();
      },
      resume: (id) => {
        engineRef.current?.resume(id);
        refresh();
      },
      cancel: (id) => {
        engineRef.current?.cancel(id);
        refresh();
      },
      clearCompleted: () => {
        engineRef.current?.clearCompleted();
        refresh();
      },
    }),
    [
      jobs,
      activeJobs,
      completedJobs,
      aggregateSpeedBps,
      activeThreadCount,
      thermalGuard,
      enqueueUrl,
      refresh,
    ],
  );

  return (
    <DownloadContext.Provider value={value}>
      {children}
    </DownloadContext.Provider>
  );
}

export function useDownloads() {
  const ctx = useContext(DownloadContext);
  if (!ctx) throw new Error("useDownloads must be used within DownloadProvider");
  return ctx;
}
