import { DownloadCloud, RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useEngineSetup } from "@/context/EngineSetupContext";
import { useSettings } from "@/context/SettingsContext";

export function EngineSetupOverlay() {
  const { tr } = useSettings();
  const { status, progress, message, ready, retry } = useEngineSetup();

  if (ready) return null;

  const failed = status === "failed";
  const stalled =
    !failed && status === "installing" && progress >= 75 && progress < 100;
  const pct = Math.max(0, Math.min(100, progress));

  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-zinc-950/95 backdrop-blur-md px-8">
      <div className="size-16 rounded-2xl bg-[#7f22fe]/20 border border-[#7f22fe]/40 flex items-center justify-center mb-6">
        <DownloadCloud className="size-8 text-[#7f22fe]" />
      </div>

      <h2 className="font-bold text-neutral-50 text-lg text-center mb-2">
        {tr("engineInstallTitle")}
      </h2>
      <p className="text-[#9f9fa9] text-sm text-center mb-6 max-w-xs leading-5">
        {failed ? message || tr("engineNotReady") : tr("engineInstallHint")}
      </p>

      {!failed && (
        <div className="w-full max-w-xs mb-3">
          <div className="flex justify-between text-[11px] text-[#9f9fa9] mb-1.5">
            <span>{message || tr("engineSettingUp")}</span>
            <span className="text-[oklch(0.78_0.13_210)] font-medium">{pct}%</span>
          </div>
          <div className="rounded-full bg-zinc-800 h-2 overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-[#7f22fe] to-[oklch(0.78_0.13_210)] transition-[width] duration-300"
              style={{ width: `${pct}%` }}
            />
          </div>
        </div>
      )}

      {(failed || stalled) && (
        <Button
          onClick={() => void retry()}
          className="mt-2 shadow-[0_0_16px_oklch(0.541_0.281_293.009/0.4)] rounded-2xl bg-[#7f22fe] text-violet-50 font-semibold h-11 px-6"
        >
          <RefreshCw className="size-4 mr-2" />
          {tr("setupEngine")}
        </Button>
      )}
    </div>
  );
}
