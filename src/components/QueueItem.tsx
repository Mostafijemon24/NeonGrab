import { Film, Music, Pause, Play, X } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import type { DownloadJob } from "@/services/downloadEngine";
import { displayJobTitle, formatBytes } from "@/lib/utils";
import { useSettings } from "@/context/SettingsContext";

type Props = {
  job: DownloadJob;
  onPause: () => void;
  onResume: () => void;
  onCancel: () => void;
};

export function QueueItem({ job, onPause, onResume, onCancel }: Props) {
  const { tr } = useSettings();
  const isVideo = job.kind === "video";
  const accent = isVideo ? "#7f22fe" : "oklch(0.78 0.13 210)";

  return (
    <Card className="backdrop-blur-xl rounded-2xl bg-zinc-900/50 border-white/10 p-4 gap-3 overflow-hidden">
      <CardContent className="p-0 gap-3 min-w-0 overflow-hidden">
        <div className="flex items-center gap-3 min-w-0 overflow-hidden">
          <div
            className="size-10 shrink-0 rounded-xl border flex justify-center items-center"
            style={{
              background: `${accent}26`,
              borderColor: `${accent}4d`,
            }}
          >
            {isVideo ? (
              <Film className="size-5" style={{ color: accent }} />
            ) : (
              <Music className="size-5" style={{ color: accent }} />
            )}
          </div>
          <div className="flex-1 min-w-0 overflow-hidden">
            <p
              className="font-medium text-neutral-50 text-sm leading-5 break-words [display:-webkit-box] [-webkit-line-clamp:2] [-webkit-box-orient:vertical] overflow-hidden"
              title={displayJobTitle(job)}
            >
              {displayJobTitle(job)}
            </p>
            <p className="text-[#9f9fa9] text-[11px] truncate">
              {formatBytes(
                job.status === "completed" ? job.downloadedBytes : job.totalBytes,
              )}{" "}
              · {tr("segments", { n: job.segments })}
            </p>
            {job.status === "failed" && job.errorMessage ? (
              <p className="text-[#a78bfa] text-[10px] truncate mt-0.5" title={job.errorMessage}>
                {job.errorMessage}
              </p>
            ) : null}
          </div>
          <span
            className="font-semibold text-xs leading-4 shrink-0"
            style={{ color: accent }}
          >
            {job.status === "failed"
              ? tr("downloadFailed")
              : job.status === "queued"
                ? "…"
                : job.status === "paused"
                  ? tr("paused")
                  : `${job.progress}%`}
          </span>
          <div className="flex gap-1 shrink-0">
            {job.status === "downloading" && (
              <button
                type="button"
                onClick={onPause}
                className="size-8 rounded-lg bg-zinc-800 flex items-center justify-center"
                aria-label={tr("pause")}
              >
                <Pause className="size-3.5 text-[#9f9fa9]" />
              </button>
            )}
            {job.status === "paused" && (
              <button
                type="button"
                onClick={onResume}
                className="size-8 rounded-lg bg-zinc-800 flex items-center justify-center"
                aria-label={tr("resume")}
              >
                <Play className="size-3.5 text-[#7f22fe]" />
              </button>
            )}
            <button
              type="button"
              onClick={onCancel}
              className="size-8 rounded-lg bg-zinc-800 flex items-center justify-center"
              aria-label={tr("cancel")}
            >
              <X className="size-3.5 text-[#9f9fa9]" />
            </button>
          </div>
        </div>
        {(job.status === "downloading" || job.status === "paused") && (
          <div className="rounded-full bg-zinc-800 w-full h-1.5 overflow-hidden">
            <div
              className="bg-gradient-to-r from-primary to-[oklch(0.78_0.13_210)] shadow-[0_0_10px_oklch(0.78_0.13_210/0.5)] rounded-full h-full transition-[width] duration-150"
              style={{ width: `${job.progress}%` }}
            />
          </div>
        )}
      </CardContent>
    </Card>
  );
}
