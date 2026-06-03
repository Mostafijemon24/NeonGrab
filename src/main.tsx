import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.tsx";
import { SettingsProvider } from "@/context/SettingsContext.tsx";
import { EngineSetupProvider } from "@/context/EngineSetupContext.tsx";
import { DownloadProvider } from "@/context/DownloadContext.tsx";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <SettingsProvider>
      <EngineSetupProvider>
        <DownloadProvider>
          <App />
        </DownloadProvider>
      </EngineSetupProvider>
    </SettingsProvider>
  </StrictMode>,
);
