import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { Preferences } from "@capacitor/preferences";
import { t, type Locale, type TranslationKey } from "@/i18n";

export type VideoQuality = "auto" | "1080" | "720" | "480" | "audio";

type Settings = {
  locale: Locale;
  maxConcurrent: number;
  maxThreads: number;
  thermalGuard: boolean;
  wifiOnly: boolean;
  quality: VideoQuality;
};

const DEFAULTS: Settings = {
  locale: "en",
  maxConcurrent: 2,
  maxThreads: 8,
  thermalGuard: true,
  wifiOnly: false,
  quality: "auto",
};

type SettingsContextValue = Settings & {
  setLocale: (l: Locale) => void;
  setMaxConcurrent: (n: number) => void;
  setMaxThreads: (n: number) => void;
  setThermalGuard: (v: boolean) => void;
  setWifiOnly: (v: boolean) => void;
  setQuality: (q: VideoQuality) => void;
  tr: (key: TranslationKey, vars?: Record<string, string | number>) => string;
};

const STORAGE_KEY = "neongrab_settings";

const SettingsContext = createContext<SettingsContextValue | null>(null);

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<Settings>(DEFAULTS);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    void (async () => {
      try {
        const { value } = await Preferences.get({ key: STORAGE_KEY });
        if (value) {
          const parsed = JSON.parse(value) as Partial<Settings>;
          setSettings({ ...DEFAULTS, ...parsed });
        }
      } catch {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) setSettings({ ...DEFAULTS, ...JSON.parse(raw) });
      }
      setLoaded(true);
    })();
  }, []);

  const persist = useCallback(async (next: Settings) => {
    const json = JSON.stringify(next);
    try {
      await Preferences.set({ key: STORAGE_KEY, value: json });
    } catch {
      localStorage.setItem(STORAGE_KEY, json);
    }
  }, []);

  const update = useCallback(
    (patch: Partial<Settings>) => {
      setSettings((prev) => {
        const next = { ...prev, ...patch };
        void persist(next);
        return next;
      });
    },
    [persist],
  );

  const tr = useCallback(
    (key: TranslationKey, vars?: Record<string, string | number>) =>
      t(settings.locale, key, vars),
    [settings.locale],
  );

  const value = useMemo<SettingsContextValue>(
    () => ({
      ...settings,
      setLocale: (locale) => update({ locale }),
      setMaxConcurrent: (maxConcurrent) =>
        update({ maxConcurrent: Math.min(4, Math.max(1, maxConcurrent)) }),
      setMaxThreads: (maxThreads) =>
        update({ maxThreads: Math.min(8, Math.max(2, maxThreads)) }),
      setThermalGuard: (thermalGuard) => update({ thermalGuard }),
      setWifiOnly: (wifiOnly) => update({ wifiOnly }),
      setQuality: (quality) => update({ quality }),
      tr,
    }),
    [settings, update, tr],
  );

  if (!loaded) return null;

  return (
    <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>
  );
}

export function useSettings() {
  const ctx = useContext(SettingsContext);
  if (!ctx) throw new Error("useSettings must be used within SettingsProvider");
  return ctx;
}
