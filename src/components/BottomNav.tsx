import { LayoutDashboard, Settings, Vault } from "lucide-react";
import { cn } from "@/lib/utils";
import { useSettings } from "@/context/SettingsContext";

export type TabId = "dashboard" | "vault" | "settings";

type Props = {
  active: TabId;
  onChange: (tab: TabId) => void;
};

export function BottomNav({ active, onChange }: Props) {
  const { tr } = useSettings();

  const items: { id: TabId; icon: typeof LayoutDashboard; label: string }[] = [
    { id: "dashboard", icon: LayoutDashboard, label: tr("dashboard") },
    { id: "vault", icon: Vault, label: tr("vault") },
    { id: "settings", icon: Settings, label: tr("settings") },
  ];

  return (
    <div className="backdrop-blur-xl bg-zinc-950/80 border-white/10 border-t border-solid shrink-0 px-5 pt-3 pb-5 safe-pb">
      <div className="flex justify-around items-center">
        {items.map(({ id, icon: Icon, label }) => {
          const on = active === id;
          return (
            <button
              key={id}
              type="button"
              onClick={() => onChange(id)}
              className={cn(
                "flex px-4 py-2 flex-col items-center gap-1 rounded-xl transition-colors",
                on &&
                  "bg-[#7f22fe]/15 border border-[#7f22fe]/40",
              )}
            >
              <Icon
                className={cn("size-5", on ? "text-[#7f22fe]" : "text-[#9f9fa9]")}
              />
              <span
                className={cn(
                  "text-[11px] font-medium",
                  on ? "text-[#7f22fe]" : "text-[#9f9fa9]",
                )}
              >
                {label}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
