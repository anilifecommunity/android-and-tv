import { createHash } from "node:crypto";
import { performance } from "node:perf_hooks";
import { readFile, unlink } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import cookieParser from "cookie-parser";
import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import multer from "multer";
import {
  authenticateAdmin,
  clearAdminSession,
  requireAdmin,
  requireAdminMutation,
  setAdminSession,
} from "./auth.js";
import { ArchiveValidationError, validateArchive } from "./archive.js";
import {
  MAX_BYTES_PER_DAY,
  MAX_COMPRESSED_BYTES,
  MAX_DESCRIPTION_CHARS,
  MAX_REPORTS_PER_DAY,
  MAX_SCREENSHOT_BYTES,
  MAX_STORED_BYTES,
  RETENTION_DAYS,
  type RuntimeConfig,
} from "./config.js";
import { AdminLoginLimiter, UploadRateLimiter, UploadRateLimitError } from "./rate-limit.js";
import type { DiagnosticTrigger, ReportMetadata, ReportStore, ScreenshotUpload } from "./types.js";

const STARTED_AT = Date.now();
const REPORT_ID_PATTERN = /^ANL-[0-9]{8}-[A-Z0-9]{10}$/;
const SAFE_FIELD_PATTERN = /^[A-Za-z0-9._-]{1,80}$/;
const VALID_TRIGGERS = new Set<DiagnosticTrigger>(["manual", "daily", "crash", "slow_start", "shortcut"]);

interface AppDependencies {
  store: ReportStore;
  config: RuntimeConfig;
}

class ClientInputError extends Error {}
class ConflictError extends Error {}
class DailyCapacityError extends Error {}

interface TurnstileResult {
  success?: boolean;
  action?: string;
  hostname?: string;
}

async function verifyTurnstile(config: RuntimeConfig, request: Request): Promise<boolean> {
  const token = request.body?.turnstileToken;
  if (typeof token !== "string" || token.length === 0 || token.length > 2_048) return false;
  try {
    const response = await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        secret: config.turnstileSecret,
        response: token,
        remoteip: request.ip ?? request.socket.remoteAddress ?? "",
      }),
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) return false;
    const result = await response.json() as TurnstileResult;
    return result.success === true &&
      result.action === "admin_login" &&
      result.hostname === config.turnstileHostname;
  } catch {
    return false;
  }
}

export function temporaryUploadPath(tempDirectory: string, path: string): string {
  const root = resolve(tempDirectory);
  const candidate = resolve(root, basename(path));
  if (candidate !== resolve(path)) throw new ClientInputError("Invalid temporary upload path");
  return candidate;
}

function utcDay(date = new Date()): string {
  return date.toISOString().slice(0, 10);
}

function reportIdIsValid(value: string): boolean {
  return REPORT_ID_PATTERN.test(value);
}

function metadataField(body: Record<string, unknown>, name: string): string {
  const value = body[name];
  if (typeof value !== "string" || !SAFE_FIELD_PATTERN.test(value)) {
    throw new ClientInputError("Invalid report metadata");
  }
  return value;
}

function descriptionField(body: Record<string, unknown>): string | undefined {
  const value = body.description;
  if (value === undefined || value === "") return undefined;
  if (typeof value !== "string" || value.length > MAX_DESCRIPTION_CHARS) {
    throw new ClientInputError("Invalid diagnostic description");
  }
  const redacted = value
    .replaceAll("\0", "")
    .replace(/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer <redacted>")
    .replace(/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/g, "<redacted>")
    .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, "<redacted-email>")
    .replace(
      /\b(password|passwd|cookie|set-cookie|authorization|access[_-]?token|refresh[_-]?token|secret)\s*([=:])\s*([^\s,;}]+)/gi,
      "$1$2<redacted>",
    )
    .trim();
  return redacted || undefined;
}

async function validateScreenshot(
  tempDirectory: string,
  file?: Express.Multer.File,
): Promise<ScreenshotUpload | null> {
  if (!file) return null;
  if (file.size <= 0 || file.size > MAX_SCREENSHOT_BYTES) {
    throw new ClientInputError("Invalid diagnostic screenshot");
  }
  const path = temporaryUploadPath(tempDirectory, file.path);
  const header = (await readFile(path)).subarray(0, 12);
  const isJpeg = header.length >= 3 && header[0] === 0xff && header[1] === 0xd8 && header[2] === 0xff;
  const isPng = header.length >= 8 &&
    [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((byte, index) => header[index] === byte);
  const isWebp = header.length >= 12 &&
    header.subarray(0, 4).toString("ascii") === "RIFF" &&
    header.subarray(8, 12).toString("ascii") === "WEBP";
  const contentType = isJpeg ? "image/jpeg" : isPng ? "image/png" : isWebp ? "image/webp" : null;
  if (!contentType || file.mimetype !== contentType) {
    throw new ClientInputError("Diagnostic screenshot must be a JPEG, PNG or WebP image");
  }
  return { path, bytes: file.size, contentType };
}

async function removeTemporary(tempDirectory: string, path?: string): Promise<void> {
  if (!path) return;
  try {
    await unlink(temporaryUploadPath(tempDirectory, path)).catch(() => undefined);
  } catch {
    // Multer paths outside its configured directory must never be deleted.
  }
}

function logEvent(name: string, attributes: Record<string, unknown> = {}): void {
  console.log(JSON.stringify({ timestamp: new Date().toISOString(), name, ...attributes }));
}

function clientAddress(request: Request): string {
  return request.ip ?? request.socket.remoteAddress ?? "unknown";
}

export function createApp({ store, config }: AppDependencies) {
  const app = express();
  const uploadRateLimiter = new UploadRateLimiter(config.rateLimitSecret);
  const loginLimiter = new AdminLoginLimiter();
  const limitUploads = (request: Request, _response: Response, next: NextFunction): void => {
    try {
      uploadRateLimiter.reserve(clientAddress(request));
      next();
    } catch (error) {
      next(error);
    }
  };
  const limitAdminLogin = (request: Request, response: Response, next: NextFunction): void => {
    if (!loginLimiter.allow(clientAddress(request))) {
      response.status(429).json({ error: "Too many sign-in attempts; try again later" });
      return;
    }
    next();
  };
  const upload = multer({
    dest: config.tempDirectory,
    limits: {
      fileSize: MAX_COMPRESSED_BYTES,
      files: 2,
      fields: 9,
      parts: 11,
      fieldSize: 4_096,
    },
  });

  app.set("trust proxy", 1);
  app.disable("x-powered-by");
  app.use(
    helmet({
      crossOriginResourcePolicy: { policy: "same-origin" },
      contentSecurityPolicy: {
        directives: {
          "default-src": ["'self'"],
          "script-src": ["'self'"],
          "style-src": ["'self'"],
          "img-src": ["'self'", "data:"],
          "connect-src": ["'self'"],
        },
      },
    }),
  );
  app.use((_request, response, next) => {
    response.set("X-Robots-Tag", "noindex, nofollow, noarchive, nosnippet, noimageindex");
    next();
  });
  app.use(cookieParser());
  app.use(express.json({ limit: "8kb" }));

  app.get("/health", async (_request, response, next) => {
    try {
      await store.healthCheck();
      response.set({
        "Cache-Control": "no-store",
        "X-Anilife-Instance-Age": String(Math.floor((Date.now() - STARTED_AT) / 1000)),
        "X-Anilife-Cold-Start": String(Date.now() - STARTED_AT < 120_000),
      });
      response.json({ status: "ok", storage: "private-hf-bucket", retentionDays: RETENTION_DAYS });
    } catch (error) {
      next(error);
    }
  });

  app.post("/v1/reports", limitUploads, upload.fields([
    { name: "report", maxCount: 1 },
    { name: "screenshot", maxCount: 1 },
  ]), async (request, response, next) => {
    const started = performance.now();
    const files = request.files as Record<string, Express.Multer.File[]> | undefined;
    const reportFile = files?.report?.[0];
    const screenshotFile = files?.screenshot?.[0];
    const tempPath = reportFile?.path;
    const screenshotTempPath = screenshotFile?.path;
    try {
      if (!reportFile || !tempPath) throw new ClientInputError("Diagnostic report is missing");
      const safeTempPath = temporaryUploadPath(config.tempDirectory, tempPath);
      if (!new Set(["application/zip", "application/octet-stream"]).has(reportFile.mimetype)) {
        throw new ClientInputError("Diagnostic report must be a ZIP archive");
      }
      const reportId = String(request.body.report_id ?? "");
      if (!reportIdIsValid(reportId)) throw new ClientInputError("Invalid report reference");
      const trigger = metadataField(request.body, "trigger") as DiagnosticTrigger;
      if (!VALID_TRIGGERS.has(trigger)) throw new ClientInputError("Invalid diagnostic trigger");
      const appVersion = metadataField(request.body, "app_version");
      const versionCode = metadataField(request.body, "version_code");
      const buildSha = metadataField(request.body, "build_sha");
      const platform = metadataField(request.body, "platform");
      const description = descriptionField(request.body);
      const screenshot = await validateScreenshot(config.tempDirectory, screenshotFile);

      const reportSha256 = createHash("sha256")
        .update(await readFile(safeTempPath))
        .digest("hex");
      if (await store.hasReport(reportId)) {
        const existing = await store.getReport(reportId);
        if (!existing || createHash("sha256").update(existing).digest("hex") !== reportSha256) {
          throw new ConflictError("Report reference already belongs to different bytes");
        }
        await removeTemporary(config.tempDirectory, tempPath);
        await removeTemporary(config.tempDirectory, screenshotTempPath);
        response.json({ status: "accepted", reportId, receivedBytes: reportFile.size });
        return;
      }

      const archiveDetails = await validateArchive(safeTempPath);
      const validationMs = performance.now() - started;
      const usage = await store.dailyUsage(utcDay());
      if (
        usage.reports >= MAX_REPORTS_PER_DAY ||
        usage.bytes + reportFile.size + (screenshot?.bytes ?? 0) > MAX_BYTES_PER_DAY
      ) {
        throw new DailyCapacityError("Daily diagnostic capacity reached; the app will retry");
      }

      const metadata: ReportMetadata = {
        reportId,
        receivedUtc: new Date().toISOString(),
        receivedBytes: reportFile.size,
        reportSha256,
        trigger,
        appVersion,
        versionCode,
        buildSha,
        platform,
        ...(description ? { description } : {}),
        ...(screenshot ? {
          screenshotBytes: screenshot.bytes,
          screenshotContentType: screenshot.contentType,
        } : {}),
        ...archiveDetails,
      };
      const storeStarted = performance.now();
      await store.putReport(reportId, safeTempPath, screenshot, metadata);
      await removeTemporary(config.tempDirectory, tempPath);
      await removeTemporary(config.tempDirectory, screenshotTempPath);
      const retentionBefore = new Date(Date.now() - RETENTION_DAYS * 24 * 60 * 60 * 1000);
      await store.cleanup(retentionBefore, MAX_STORED_BYTES);
      const storeMs = performance.now() - storeStarted;
      logEvent("report.accepted", {
        reportId,
        trigger,
        bytes: reportFile.size,
        screenshotBytes: screenshot?.bytes ?? 0,
        hasDescription: Boolean(description),
      });
      response.set({
        "Cache-Control": "no-store",
        "Server-Timing": `validate;dur=${validationMs.toFixed(1)}, store;dur=${storeMs.toFixed(1)}`,
        "X-Anilife-Instance-Age": String(Math.floor((Date.now() - STARTED_AT) / 1000)),
        "X-Anilife-Cold-Start": String(Date.now() - STARTED_AT < 120_000),
      });
      response.json({ status: "accepted", reportId, receivedBytes: reportFile.size });
    } catch (error) {
      await removeTemporary(config.tempDirectory, tempPath);
      await removeTemporary(config.tempDirectory, screenshotTempPath);
      next(error);
    }
  });

  app.get("/api/turnstile/config", (_request, response) => {
    response.set("Cache-Control", "no-store").json({ siteKey: config.turnstileSiteKey });
  });

  app.post("/api/admin/login", limitAdminLogin, async (request, response) => {
    if (!(await verifyTurnstile(config, request))) {
      response.status(403).json({ error: "Bot verification failed" });
      return;
    }
    if (!authenticateAdmin(config, request.body?.accessKey)) {
      response.status(401).json({ error: "Incorrect administrator access key" });
      return;
    }
    setAdminSession(response, config);
    response.set("Cache-Control", "no-store").json({ authenticated: true });
  });

  app.post("/api/admin/logout", requireAdmin(config), requireAdminMutation, (_request, response) => {
    clearAdminSession(response, config);
    response.json({ authenticated: false });
  });

  app.get("/api/admin/session", requireAdmin(config), (_request, response) => {
    response.set("Cache-Control", "no-store").json({ authenticated: true });
  });

  app.get("/api/admin/reports", requireAdmin(config), async (request, response, next) => {
    try {
      const requested = Number(request.query.limit ?? 100);
      const limit = Math.min(250, Math.max(1, Number.isFinite(requested) ? requested : 100));
      const reports = await store.listReports(limit);
      const today = await store.dailyUsage(utcDay());
      response.set("Cache-Control", "no-store").json({ reports, today, retentionDays: RETENTION_DAYS });
    } catch (error) {
      next(error);
    }
  });

  app.get("/api/admin/reports/:reportId/download", requireAdmin(config), async (request, response, next) => {
    try {
      const reportId = String(request.params.reportId);
      if (!reportIdIsValid(reportId)) throw new ClientInputError("Invalid report reference");
      const report = await store.getReport(reportId);
      if (!report) {
        response.status(404).json({ error: "Report not found" });
        return;
      }
      response.set({
        "Content-Type": "application/zip",
        "Content-Disposition": `attachment; filename="${reportId}.zip"`,
        "Cache-Control": "private, no-store",
      });
      response.send(report);
    } catch (error) {
      next(error);
    }
  });

  app.get("/api/admin/reports/:reportId/screenshot", requireAdmin(config), async (request, response, next) => {
    try {
      const reportId = String(request.params.reportId);
      if (!reportIdIsValid(reportId)) throw new ClientInputError("Invalid report reference");
      const screenshot = await store.getScreenshot(reportId);
      if (!screenshot) {
        response.status(404).json({ error: "Screenshot not found" });
        return;
      }
      response.set({
        "Content-Type": screenshot.contentType,
        "Content-Disposition": `inline; filename="${reportId}-screenshot"`,
        "Cache-Control": "private, no-store",
      });
      response.send(screenshot.bytes);
    } catch (error) {
      next(error);
    }
  });

  app.delete(
    "/api/admin/reports/:reportId",
    requireAdmin(config),
    requireAdminMutation,
    async (request, response, next) => {
      try {
        const reportId = String(request.params.reportId);
        if (!reportIdIsValid(reportId)) throw new ClientInputError("Invalid report reference");
        const deleted = await store.deleteReport(reportId);
        response.status(deleted ? 200 : 404).json({ deleted });
      } catch (error) {
        next(error);
      }
    },
  );

  const clientRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../client");
  app.use(express.static(clientRoot, { index: false, maxAge: "1h" }));
  app.get("/{*path}", (_request, response) => response.sendFile(resolve(clientRoot, "index.html")));

  app.use((error: unknown, _request: Request, response: Response, _next: NextFunction) => {
    if (error instanceof multer.MulterError && error.code === "LIMIT_FILE_SIZE") {
      response.status(413).json({ error: "Diagnostic report is too large" });
      return;
    }
    if (error instanceof ClientInputError || error instanceof ArchiveValidationError) {
      response.status(400).json({ error: error.message });
      return;
    }
    if (error instanceof ConflictError) {
      response.status(409).json({ error: error.message });
      return;
    }
    if (error instanceof UploadRateLimitError || error instanceof DailyCapacityError) {
      response.status(429).json({ error: error.message });
      return;
    }
    logEvent("request.failed", { errorType: error instanceof Error ? error.name : "UnknownError" });
    response.status(503).json({ error: "Diagnostic storage is temporarily unavailable" });
  });

  return app;
}
