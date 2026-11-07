import { tmpdir } from "node:os";
import { resolve } from "node:path";

export const MAX_COMPRESSED_BYTES = 25_000_000;
export const MAX_SCREENSHOT_BYTES = 5_000_000;
export const MAX_DESCRIPTION_CHARS = 2_000;
export const MAX_EXPANDED_BYTES = 50_000_000;
export const MAX_ZIP_ENTRIES = 64;
export const RETENTION_DAYS = 30;
export const MAX_STORED_BYTES = 20_000_000_000;
export const MAX_REPORTS_PER_IP_HOUR = 8;
export const MAX_REPORTS_PER_DAY = 200;
export const MAX_BYTES_PER_DAY = 2_000_000_000;

export interface RuntimeConfig {
  adminAccessKey: string;
  turnstileSecret: string;
  turnstileSiteKey: string;
  turnstileHostname: string;
  tempDirectory: string;
  secureCookies: boolean;
}

export function runtimeConfig(overrides: Partial<RuntimeConfig> = {}): RuntimeConfig {
  const config: RuntimeConfig = {
    adminAccessKey: process.env.ADMIN_ACCESS_KEY ?? "",
    turnstileSecret: process.env.TURNSTILE_SECRET ?? "",
    turnstileSiteKey: process.env.TURNSTILE_SITE_KEY ?? "",
    turnstileHostname: process.env.TURNSTILE_HOSTNAME ?? "",
    tempDirectory: resolve(process.env.DIAGNOSTICS_TEMP_DIR ?? tmpdir()),
    secureCookies: process.env.NODE_ENV === "production",
    ...overrides,
  };
  if (config.adminAccessKey.length < 24) {
    throw new Error("ADMIN_ACCESS_KEY must contain at least 24 characters");
  }
  if (!config.turnstileSecret || !config.turnstileSiteKey || !config.turnstileHostname) {
    throw new Error("Turnstile configuration is required");
  }
  return config;
}
