import { useState } from "react";
import {
  BatteryCharging,
  Bell,
  ClipboardPaste,
  Cpu,
  DownloadCloud,
  Gauge,
  Globe,
  Link,
  ListVideo,
  Zap,
} from "lucide-react";
import { Clipboard } from "@capacitor/clipboard";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { QueueItem } from "@/components/QueueItem";
import { useDownloads } from "@/context/DownloadContext";
import { useSettings } from "@/context/SettingsContext";
import { extractUrlsFromText } from "@/services/urlInput";
import { formatSpeed } from "@/lib/utils";
import { cn } from "@/lib/utils";

export function DashboardScreen() {
  const { tr } = useSettings();
  const {
    activeJobs,
    aggregateSpeedBps,
    activeThreadCount,
    thermalActive,
    enqueueUrl,
    pause,
    resume,
    cancel,
  } = useDownloads();

  const [mode, setMode] = useState<"single" | "batch">("single");
  const [url, setUrl] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [batchHint, setBatchHint] = useState<string | null>(null);

  const applyPastedText = (text: string) => {
    const urls = extractUrlsFromText(text);
    if (urls.length === 0) {
      setUrl(text.trim());
      return;
    }
    setUrl(urls.join("\n"));
    setError(null);
  };

  const pasteFromClipboard = async () => {
    try {
      const { value } = await Clipboard.read();
      if (value) applyPastedText(value);
    } catch {
      try {
        const text = await navigator.clipboard.readText();
        applyPastedText(text);
      } catch {
        /* ignore */
      }
    }
  };

  const handleDownload = async () => {
    if (!url.trim()) return;
    setLoading(true);
    setError(null);
    setBatchHint(null);
    const { ok, count, started, error: errKey } = await enqueueUrl(url, mode === "batch");
    setLoading(false);
    if (!ok) {
      const known = [
        "engineNotReady",
        "folderRequired",
        "invalidUrl",
        "probeFailed",
        "wifiOnlyBlocked",
      ] as const;
      const key = known.includes(errKey as (typeof known)[number])
        ? (errKey as (typeof known)[number])
        : "invalidUrl";
      setError(tr(key));
      return;
    }
    if (started === 0) {
      setError(tr("noDownloadStarted"));
      return;
    }
    if (count > 1) setBatchHint(tr("itemsFound", { count }));
    setUrl("");
  };

  return (
    <div className="relative overflow-y-auto px-5 pt-8 pb-4 flex-1 min-h-0">
      <div className="flex justify-between items-center">
        <div className="flex items-center gap-2">
          <div className="size-9 shadow-[0_0_18px_oklch(0.541_0.281_293.009/0.45)] rounded-xl bg-[#7f22fe]/20 border-[#7f22fe]/40 border flex justify-center items-center">
            <DownloadCloud className="size-5 text-[#7f22fe]" />
          </div>
          <div className="leading-tight flex flex-col">
            <span className="font-bold text-neutral-50 text-base leading-6 tracking-tight">
              {tr("appName")}
            </span>
            <span className="uppercase text-[#9f9fa9] text-[11px] tracking-[3.2px]">
              {tr("appTagline")}
            </span>
          </div>
        </div>
        <Button
          variant="ghost"
          size="icon"
          className="size-9 rounded-full bg-zinc-900/60 border-white/10 border"
          aria-label={tr("notifications")}
        >
          <Bell className="size-4 text-[#9f9fa9]" />
        </Button>
      </div>

      <div className="grid grid-cols-2 mt-6 gap-3">
        <Card className="backdrop-blur-xl shadow-[0_0_24px_oklch(0.541_0.281_293.009/0.12)] rounded-2xl bg-zinc-900/50 border-white/10 p-4 gap-3">
          <CardHeader className="p-0 gap-1">
            <div className="text-[#9f9fa9] flex items-center gap-1.5">
              <Gauge className="size-3.5 text-[oklch(0.78_0.13_210)]" />
              <span className="text-[11px]">{tr("speed")}</span>
            </div>
          </CardHeader>
          <CardContent className="p-0">
            <div className="flex items-end gap-1">
              <span className="text-[oklch(0.78_0.13_210)] font-bold text-3xl leading-9">
                {aggregateSpeedBps > 0 ? formatSpeed(aggregateSpeedBps) : "0.0"}
              </span>
              <span className="text-[#9f9fa9] text-[11px] leading-4 mb-1.5">
                MB/s
              </span>
            </div>
          </CardContent>
        </Card>
        <Card className="backdrop-blur-xl shadow-[0_0_24px_oklch(0.541_0.281_293.009/0.12)] rounded-2xl bg-zinc-900/50 border-white/10 p-4 gap-3">
          <CardHeader className="p-0 gap-1">
            <div className="text-[#9f9fa9] flex items-center gap-1.5">
              <Cpu className="size-3.5 text-[#7f22fe]" />
              <span className="text-[11px]">{tr("threads")}</span>
            </div>
          </CardHeader>
          <CardContent className="p-0">
            <div className="flex items-end gap-1">
              <span className="font-bold text-[#7f22fe] text-3xl leading-9">
                {String(activeThreadCount).padStart(2, "0")}
              </span>
              <span className="text-[#9f9fa9] text-[11px] leading-4 mb-1.5">
                {tr("active")}
              </span>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card className="backdrop-blur-xl shadow-[0_0_30px_oklch(0.541_0.281_293.009/0.15)] rounded-3xl bg-zinc-900/50 border-white/10 mt-5 p-5 gap-4">
        <CardHeader className="p-0 gap-3">
          <div className="grid grid-cols-2 rounded-2xl bg-zinc-800/60 border-white/10 border p-1 gap-1">
            <button
              type="button"
              onClick={() => setMode("single")}
              className={cn(
                "font-medium rounded-xl text-xs leading-4 flex py-2 justify-center items-center gap-1.5",
                mode === "single"
                  ? "shadow-[0_0_16px_oklch(0.541_0.281_293.009/0.5)] bg-[#7f22fe] text-violet-50"
                  : "text-[#9f9fa9]",
              )}
            >
              <Link className="size-3.5" />
              {tr("singleLink")}
            </button>
            <button
              type="button"
              onClick={() => setMode("batch")}
              className={cn(
                "font-medium rounded-xl text-xs leading-4 flex py-2 justify-center items-center gap-1.5",
                mode === "batch"
                  ? "shadow-[0_0_16px_oklch(0.541_0.281_293.009/0.5)] bg-[#7f22fe] text-violet-50"
                  : "text-[#9f9fa9]",
              )}
            >
              <ListVideo className="size-3.5" />
              {tr("batchPlaylist")}
            </button>
          </div>
        </CardHeader>
        <CardContent className="p-0 gap-3">
          <div className="rounded-2xl bg-zinc-800/60 border-white/10 border flex px-3 py-2.5 items-center gap-2">
            <Globe className="size-4 shrink-0 text-[#9f9fa9]" />
            <textarea
              value={url}
              rows={mode === "batch" ? 3 : 1}
              onChange={(e) => setUrl(e.target.value)}
              placeholder={
                mode === "batch" ? tr("pasteBatch") : tr("pasteUrl")
              }
              className="bg-transparent outline-none resize-none text-sm leading-5 flex-1 text-neutral-50 placeholder:text-[#9f9fa9]/70 min-w-0"
            />
            <button type="button" onClick={pasteFromClipboard} aria-label="Paste">
              <ClipboardPaste className="size-4 text-[oklch(0.78_0.13_210)]" />
            </button>
          </div>
          {error && (
            <p className="text-red-400/90 text-xs px-1">{error}</p>
          )}
          {batchHint && (
            <p className="text-[oklch(0.78_0.13_210)] text-xs px-1">{batchHint}</p>
          )}
          <Button
            disabled={loading || !url.trim()}
            onClick={handleDownload}
            className="shadow-[0_0_22px_oklch(0.541_0.281_293.009/0.5)] font-semibold rounded-2xl bg-[#7f22fe] text-violet-50 w-full h-11 disabled:opacity-50"
          >
            <Zap className="size-4" />
            {loading
              ? tr("analyzing")
              : mode === "batch"
                ? tr("fetchPlaylist")
                : tr("startDownload")}
          </Button>
        </CardContent>
      </Card>

      <div className="flex mt-6 justify-between items-center">
        <span className="font-semibold text-neutral-50 text-sm leading-5">
          {tr("activeQueue")}
        </span>
        <span className="text-[#9f9fa9] text-[11px]">
          {tr("hardwareSegmenting")}
        </span>
      </div>

      <div className="flex mt-3 flex-col gap-3 pb-2">
        {activeJobs.length === 0 ? (
          <Card className="backdrop-blur-xl rounded-2xl bg-zinc-900/50 border-white/10 p-4">
            <CardContent className="p-0">
              <p className="text-[#9f9fa9] text-sm text-center py-2">
                {tr("noActiveDownloads")}
              </p>
            </CardContent>
          </Card>
        ) : (
          activeJobs.map((job) => (
            <QueueItem
              key={job.id}
              job={job}
              onPause={() => pause(job.id)}
              onResume={() => resume(job.id)}
              onCancel={() => cancel(job.id)}
            />
          ))
        )}

        {thermalActive && (
          <Card className="backdrop-blur-xl rounded-2xl bg-zinc-900/50 border-white/10 p-4">
            <CardContent className="p-0">
              <div className="text-[#9f9fa9] text-[11px] flex items-center gap-2">
                <BatteryCharging className="size-4 shrink-0 text-[oklch(0.78_0.13_210)]" />
                <span>{tr("thermalGuard")}</span>
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}
