import { useState } from "react";
import { BottomNav, type TabId } from "@/components/BottomNav";
import { EngineSetupOverlay } from "@/components/EngineSetupOverlay";
import { DashboardScreen } from "@/screens/DashboardScreen";
import { VaultScreen } from "@/screens/VaultScreen";
import { SettingsScreen } from "@/screens/SettingsScreen";
import { useEngineSetup } from "@/context/EngineSetupContext";

export default function App() {
  const [tab, setTab] = useState<TabId>("dashboard");
  const { ready } = useEngineSetup();

  return (
    <div className="bg-zinc-950 text-neutral-50 h-full w-full flex flex-col overflow-hidden">
      <EngineSetupOverlay />
      <div className="relative flex flex-col flex-1 min-h-0 w-full overflow-hidden">
        <div className="size-72 blur-[90px] rounded-full bg-[#7f22fe]/20 absolute -left-20 -top-24 pointer-events-none" />
        <div className="size-64 bg-[oklch(0.78_0.13_210)]/18 blur-[90px] rounded-full absolute -right-24 top-40 pointer-events-none" />

        <div
          className="relative flex flex-col flex-1 min-h-0 z-10"
          aria-hidden={!ready}
          style={ready ? undefined : { pointerEvents: "none", opacity: 0.35 }}
        >
          {tab === "dashboard" && <DashboardScreen />}
          {tab === "vault" && <VaultScreen />}
          {tab === "settings" && <SettingsScreen />}
          <BottomNav active={tab} onChange={setTab} />
        </div>
      </div>
    </div>
  );
}
