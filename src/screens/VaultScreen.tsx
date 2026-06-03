import { Download, Film, Music, Trash2 } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { useEffect, useState } from "react";
import { useDownloads } from "@/context/DownloadContext";
import { useSettings } from "@/context/SettingsContext";
import { formatFolderLabel, getDownloadFolder } from "@/services/downloadLocation";
import { formatBytes } from "@/lib/utils";

export function VaultScreen() {
  const { tr } = useSettings();
  const { completedJobs, clearCompleted } = useDownloads();
  const [saveLabel, setSaveLabel] = useState("");

  useEffect(() => {
    void getDownloadFolder().then((f) => {
      const label = formatFolderLabel(f);
      setSaveLabel(label || tr("noFolderSelected"));
    });
  }, [tr]);

  return (
    <div className="relative overflow-y-auto px-5 pt-8 pb-4 flex-1 min-h-0">
      <div className="flex justify-between items-start mb-6">
        <div>
          <h1 className="font-bold text-neutral-50 text-xl">{tr("vault")}</h1>
          <p className="text-[#9f9fa9] text-xs mt-1 truncate" title={saveLabel}>
            {saveLabel}
          </p>
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
              className="backdrop-blur-xl rounded-2xl bg-zinc-900/50 border-white/10 p-4"
            >
              <CardContent className="p-0 flex items-center gap-3">
                <div className="size-10 shrink-0 rounded-xl bg-[#7f22fe]/15 border border-[#7f22fe]/30 flex justify-center items-center">
                  {job.kind === "video" ? (
                    <Film className="size-5 text-[#7f22fe]" />
                  ) : (
                    <Music className="size-5 text-[oklch(0.78_0.13_210)]" />
                  )}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium text-neutral-50 text-sm">
                    {job.title}
                  </p>
                  <p className="text-[#9f9fa9] text-[11px]">
                    {formatBytes(job.totalBytes)} · {tr("downloadComplete")}
                  </p>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
