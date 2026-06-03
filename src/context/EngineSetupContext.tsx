import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Capacitor } from "@capacitor/core";
import {
  getYtDlpAvailability,
  setupDownloadEngine,
  subscribeEngineSetupEvents,
} from "@/services/nativeYtDlp";

export type EngineSetupStatus = "idle" | "installing" | "ready" | "failed";

type EngineSetupContextValue = {
  status: EngineSetupStatus;
  progress: number;
  message: string;
  ready: boolean;
  retry: () => Promise<void>;
};

const EngineSetupContext = createContext<EngineSetupContextValue | null>(null);

export function EngineSetupProvider({ children }: { children: ReactNode }) {
  const isAndroid = Capacitor.getPlatform() === "android";
  const [status, setStatus] = useState<EngineSetupStatus>(
    isAndroid ? "installing" : "ready",
  );
  const [progress, setProgress] = useState(0);
  const [message, setMessage] = useState("");
  const startedRef = useRef(false);

  const applyReady = useCallback(() => {
    setStatus("ready");
    setProgress(100);
    setMessage("");
  }, []);

  const runSetup = useCallback(
    async (retry = false) => {
      if (!isAndroid) {
        applyReady();
        return;
      }

      setStatus("installing");
      setProgress(retry ? 5 : 0);
      setMessage("");

      const result = await setupDownloadEngine(retry);
      if (result.available) {
        applyReady();
      } else {
        setStatus("failed");
        setProgress(0);
        setMessage(result.message ?? "Engine setup failed");
      }
    },
    [isAndroid, applyReady],
  );

  useEffect(() => {
    if (!isAndroid) return;

    const unsubscribe = subscribeEngineSetupEvents({
      onProgress: (e) => {
        setStatus("installing");
        setProgress(e.progress);
        setMessage(e.message);
      },
      onComplete: () => {
        applyReady();
      },
      onFailed: (e) => {
        setStatus("failed");
        setMessage(e.message);
      },
    });

    if (startedRef.current) return unsubscribe;
    startedRef.current = true;

    void (async () => {
      const current = await getYtDlpAvailability(true);
      if (current.available) {
        applyReady();
        return;
      }
      await runSetup(false);
    })();

    return unsubscribe;
  }, [isAndroid, runSetup, applyReady]);

  const value = useMemo<EngineSetupContextValue>(
    () => ({
      status,
      progress,
      message,
      ready: status === "ready",
      retry: async () => {
        await runSetup(true);
      },
    }),
    [status, progress, message, runSetup],
  );

  return (
    <EngineSetupContext.Provider value={value}>
      {children}
    </EngineSetupContext.Provider>
  );
}

export function useEngineSetup() {
  const ctx = useContext(EngineSetupContext);
  if (!ctx) {
    throw new Error("useEngineSetup must be used within EngineSetupProvider");
  }
  return ctx;
}
