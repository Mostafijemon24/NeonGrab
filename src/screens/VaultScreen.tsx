import { useEffect, useState } from "react";
import { Capacitor } from "@capacitor/core";
import { ChevronRight, Download, Film, Music, Trash2 } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { useDownloads } from "@/context/DownloadContext";
import { useSettings } from "@/context/SettingsContext";
import { formatFolderLabel, getDownloadFolder } from "@/services/downloadLocation";
import type { DownloadJob } from "@/services/downloadEngine";
import { openDownloadedFile } from "@/services/openMedia";
import {
  displayJobTitle,
  formatBytes,
  folderFromDisplayPath,
  shortenText,
} from "@/lib/utils";
import { cn } from "@/lib/utils";

export function VaultScreen() {
  const { tr } = useSettings();
  const { completedJobs, clearCompleted } = useDownloads();
  const [saveLabel, setSaveLabel] = useState("");
  const [openError, setOpenError] = useState<string | null>(null);

  useEffect(() => {
    void getDownloadFolder().then((f) => {
      const label = formatFolderLabel(f);
      setSaveLabel(label || tr("noFolderSelected"));
    });
  }, [tr]);

  const handleOpen = async (job: DownloadJob) => {
    if (Capacitor.getPlatform() !== "android") return;
    setOpenError(null);
    try {
      await openDownloadedFile(job);
    } catch {
      setOpenError(tr("cannotOpenFile"));
    }
  };

  const canOpen = (job: DownloadJob) =>
    Capacitor.getPlatform() === "android" && !!(job.openUri || job.filePath);

  return (
    <div className="relative overflow-y-auto px-5 pt-8 pb-4 flex-1 min-h-0">
      <div className="flex justify-between items-start mb-6">
        <div>
          <h1 className="font-bold text-neutral-50 text-xl">{tr("vault")}</h1>
          <p className="text-[#9f9fa9] text-xs mt-1 truncate" title={saveLabel}>
            {saveLabel}
          </p>
          {Capacitor.getPlatform() === "android" && completedJobs.length > 0 && (
            <p className="text-[#9f9fa9] text-[10px] mt-1">{tr("tapToOpen")}</p>
          )}
        </div>
        {completedJobs.length > 0 && (
          <Button
            variant="ghost"
            onClick={clearCompleted}
            className="text-[#9f9fa9] text-xs h-8 px-2"
          >
            <Trash2 className="size-3.5 mr-1" />
            {tr("clearCompleted")}
          </Button>
        )}
      </div>

      {openError && (
        <p className="text-red-400/90 text-xs mb-3 text-center">{openError}</p>
      )}

      {completedJobs.length === 0 ? (
        <Card className="backdrop-blur-xl rounded-2xl bg-zinc-900/50 border-white/10 p-8">
          <CardContent className="p-0 flex flex-col items-center gap-3">
            <Download className="size-10 text-[#7f22fe]/40" />
            <p className="text-[#9f9fa9] text-sm text-center">{tr("vaultEmpty")}</p>
          </CardContent>
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {completedJobs.map((job) => (
            <Card
              key={job.id}
              className={cn(
                "backdrop-blur-xl rounded-2xl bg-zinc-900/50 border-white/10 p-4 transition-colors overflow-hidden",
                canOpen(job) && "active:bg-zinc-800/60 cursor-pointer",
              )}
            >
              <button
                type="button"
                disabled={!canOpen(job)}
                onClick={() => void handleOpen(job)}
                className="w-full min-w-0 overflow-hidden text-left disabled:cursor-default"
              >
                <CardContent className="p-0 flex items-center gap-3 min-w-0 overflow-hidden">
                  <div className="size-10 shrink-0 rounded-xl bg-[#7f22fe]/15 border border-[#7f22fe]/30 flex justify-center items-center">
                    {job.kind === "video" ? (
                      <Film className="size-5 text-[#7f22fe]" />
                    ) : (
                      <Music className="size-5 text-[oklch(0.78_0.13_210)]" />
                    )}
                  </div>
                  <div className="flex-1 min-w-0 overflow-hidden">
                    <p
                      className="font-medium text-neutral-50 text-sm leading-snug break-words [display:-webkit-box] [-webkit-line-clamp:2] [-webkit-box-orient:vertical] overflow-hidden"
                      title={displayJobTitle(job)}
                    >
                      {displayJobTitle(job)}
                    </p>
                    <p
                      className="text-[#9f9fa9] text-[11px] truncate mt-0.5"
                      title={job.filePath}
                    >
                      {formatBytes(job.totalBytes)}
                      {job.filePath
                        ? ` · ${tr("savedTo", {
                            path: shortenText(
                              folderFromDisplayPath(job.filePath),
                              28,
                            ),
                          })}`
                        : ` · ${tr("downloadComplete")}`}
                    </p>
                  </div>
                  {canOpen(job) && (
                    <ChevronRight className="size-4 text-[#7f22fe] shrink-0" />
                  )}
                </CardContent>
              </button>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
