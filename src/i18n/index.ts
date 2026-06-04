import { en, type TranslationKey } from "./en";
import { bn } from "./bn";

export type Locale = "en" | "bn";

const catalogs: Record<Locale, Record<TranslationKey, string>> = { en, bn };

export function t(
  locale: Locale,
  key: TranslationKey,
  vars?: Record<string, string | number>,
): string {
  let text = catalogs[locale][key] ?? catalogs.en[key] ?? key;
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.replace(`{${k}}`, String(v));
    }
  }
  return text;
}

export { type TranslationKey };
