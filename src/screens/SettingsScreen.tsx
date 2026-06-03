import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Capacitor } from "@capacitor/core";
import { ChevronRight, DownloadCloud, FolderOpen } from "lucide-react";
import { getYtDlpAvailability, type YtDlpAvailability } from "@/services/nativeYtDlp";
import {
  formatFolderLabel,
  getDownloadFolder,
  pickDownloadFolder,
} from "@/services/downloadLocation";
import type { DownloadFolderInfo } from "@/plugins/yt-dlp";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { useSettings, type VideoQuality } from "@/context/SettingsContext";
import type { Locale } from "@/i18n";
import { cn } from "@/lib/utils";

function Toggle({
  on,
  onChange,
}: {
  on: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      onClick={() => onChange(!on)}
      className={cn(
        "w-11 h-6 rounded-full transition-colors relative shrink-0",
        on ? "bg-[#7f22fe]" : "bg-zinc-700",
      )}
    >
      <span
        className={cn(
          "absolute top-0.5 size-5 rounded-full bg-white transition-[left]",
          on ? "left-[22px]" : "left-0.5",
        )}
      />
    </button>
  );
}

export function SettingsScreen() {
  const {
    tr,
    locale,
    setLocale,
    maxConcurrent,
    setMaxConcurrent,
    maxThreads,
    setMaxThreads,
    thermalGuard,
    setThermalGuard,
    wifiOnly,
    setWifiOnly,
    quality,
    setQuality,
  } = useSettings();

  const [ytdlp, setYtdlp] = useState<YtDlpAvailability | null>(null);
  const [folder, setFolder] = useState<DownloadFolderInfo | null>(null);
  const [picking, setPicking] = useState(false);
  const [pickError, setPickError] = useState<string | null>(null);
  const isAndroid = Capacitor.getPlatform() === "android";

  const refreshFolder = useCallback(async () => {
    try {
      setFolder(await getDownloadFolder());
    } catch {
      setFolder({ configured: false, displayName: "" });
    }
  }, []);

  useEffect(() => {
    if (isAndroid) void getYtDlpAvailability().then(setYtdlp);
    void refreshFolder();
  }, [isAndroid, refreshFolder]);

  const handlePickFolder = async () => {
    if (!isAndroid) return;
    setPicking(true);
    setPickError(null);
    try {
      const result = await pickDownloadFolder();
      setFolder(result);
    } catch {
      setPickError(tr("folderPickCancelled"));
    } finally {
      setPicking(false);
    }
  };

  const qualities: { id: VideoQuality; label: string }[] = [
    { id: "auto", label: tr("qualityAuto") },
    { id: "1080", label: tr("quality1080") },
    { id: "720", label: tr("quality720") },
    { id: "480", label: tr("quality480") },
    { id: "audio", label: tr("qualityAudio") },
  ];

  const folderLabel = folder ? formatFolderLabel(folder) : "";

  return (
    <div className="relative overflow-y-auto px-5 pt-8 pb-4 flex-1 min-h-0">
      <div className="flex items-center gap-2 mb-6">
        <div className="size-9 rounded-xl bg-[#7f22fe]/20 border border-[#7f22fe]/40 flex justify-center items-center">
          <DownloadCloud className="size-5 text-[#7f22fe]" />
        </div>
        <h1 className="font-bold text-neutral-50 text-xl">{tr("settings")}</h1>
      </div>

      <Card className="backdrop-blur-xl rounded-2xl bg-zinc-900/50 border-white/10 mb-4">
        <CardContent className="p-4 gap-4">
          <div>
            <p className="text-sm font-medium text-neutral-50 mb-2">
              {tr("language")}
            </p>
            <div className="grid grid-cols-2 gap-2">
              {(["en", "bn"] as Locale[]).map((l) => (
                <button
                  key={l}
                  type="button"
                  onClick={() => setLocale(l)}
                  className={cn(
                    "py-2.5 rounded-xl text-sm font-medium border",
                    locale === l
                      ? "bg-[#7f22fe] border-[#7f22fe] text-violet-50"
                      : "bg-zinc-800/60 border-white/10 text-[#9f9fa9]",
                  )}
                >
                  {l === "en" ? tr("english") : tr("bengali")}
                </button>
              ))}
            </div>
          </div>

          <div className="border-t border-white/10 pt-4">
            <div className="flex items-center gap-2 mb-2">
              <FolderOpen className="size-4 text-[#7f22fe]" />
              <p className="text-sm font-medium text-neutral-50">
                {tr("downloadLocation")}
              </p>
            </div>
            <p className="text-[#9f9fa9] text-[11px] mb-3">{tr("downloadLocationHint")}</p>

            <button
              type="button"
              onClick={() => void handlePickFolder()}
              disabled={!isAndroid || picking}
              className={cn(
                "w-full rounded-2xl border border-white/10 bg-zinc-800/50 px-4 py-3.5",
                "flex items-center gap-3 text-left transition-colors",
                isAndroid && !picking && "active:bg-zinc-800/80",
                (!isAndroid || picking) && "opacity-60",
              )}
            >
              <div className="size-10 shrink-0 rounded-xl bg-[#7f22fe]/15 border border-[#7f22fe]/30 flex items-center justify-center">
                <FolderOpen className="size-5 text-[#7f22fe]" />
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-neutral-50 truncate">
                  {folderLabel || tr("noFolderSelected")}
                </p>
                <p className="text-[11px] text-[#9f9fa9] mt-0.5">
                  {isAndroid
                    ? folder?.configured
                      ? tr("changeDownloadFolder")
                      : tr("pickDownloadFolder")
                    : tr("folderPickerAndroidOnly")}
                </p>
              </div>
              {isAndroid && <ChevronRight className="size-4 text-[#9f9fa9] shrink-0" />}
            </button>

            {isAndroid && (
              <Button
                onClick={() => void handlePickFolder()}
                disabled={picking}
                className="mt-3 shadow-[0_0_16px_oklch(0.541_0.281_293.009/0.35)] font-semibold rounded-2xl bg-[#7f22fe] text-violet-50 w-full h-11"
              >
                {picking
                  ? tr("openingFolderPicker")
                  : folder?.configured
                    ? tr("changeDownloadFolder")
                    : tr("pickDownloadFolder")}
              </Button>
            )}

            {pickError && (
              <p className="text-[11px] text-[#9f9fa9] mt-2 text-center">{pickError}</p>
            )}
          </div>

          <Row label={tr("thermalGuardToggle")}>
            <Toggle on={thermalGuard} onChange={setThermalGuard} />
          </Row>

          <Row label={tr("wifiOnly")}>
            <Toggle on={wifiOnly} onChange={setWifiOnly} />
          </Row>

          <div>
            <p className="text-sm text-neutral-50 mb-2">{tr("maxConcurrent")}</p>
            <input
              type="range"
              min={1}
              max={4}
              value={maxConcurrent}
              onChange={(e) => setMaxConcurrent(Number(e.target.value))}
              className="w-full accent-[#7f22fe]"
            />
            <p className="text-[#9f9fa9] text-xs mt-1 text-right">{maxConcurrent}</p>
          </div>

          <div>
            <p className="text-sm text-neutral-50 mb-2">{tr("threads")}</p>
            <input
              type="range"
              min={2}
              max={8}
              value={maxThreads}
              onChange={(e) => setMaxThreads(Number(e.target.value))}
              className="w-full accent-[#7f22fe]"
            />
            <p className="text-[#9f9fa9] text-xs mt-1 text-right">{maxThreads}</p>
          </div>

          <div>
            <p className="text-sm text-neutral-50 mb-2">{tr("videoQuality")}</p>
            <div className="flex flex-col gap-1.5">
              {qualities.map((q) => (
                <button
                  key={q.id}
                  type="button"
                  onClick={() => setQuality(q.id)}
                  className={cn(
                    "text-left py-2 px-3 rounded-xl text-sm border",
                    quality === q.id
                      ? "bg-[#7f22fe]/20 border-[#7f22fe]/50 text-violet-100"
                      : "bg-zinc-800/40 border-white/5 text-[#9f9fa9]",
                  )}
                >
                  {q.label}
                </button>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>

      <Card className="backdrop-blur-xl rounded-2xl bg-zinc-900/50 border-white/10">
        <CardContent className="p-4 gap-2">
          <p className="text-sm font-medium text-neutral-50 mb-1">{tr("about")}</p>
          <p className="text-[#9f9fa9] text-xs leading-5">{tr("aboutText")}</p>
          {ytdlp && (
            <p className="text-[11px] text-[#9f9fa9] border-t border-white/10 pt-2 mt-2">
              yt-dlp:{" "}
              {ytdlp.available
                ? `ready ${ytdlp.version ?? ""}`.trim()
                : ytdlp.message ?? "not installed"}
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function Row({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <p className="text-sm text-neutral-50 flex-1">{label}</p>
      {children}
    </div>
  );
}
