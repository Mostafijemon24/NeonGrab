import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.tsx";
import { SettingsProvider } from "@/context/SettingsContext.tsx";
import { DownloadProvider } from "@/context/DownloadContext.tsx";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <SettingsProvider>
      <DownloadProvider>
        <App />
      </DownloadProvider>
    </SettingsProvider>
  </StrictMode>,
);
