import { describe, expect, it } from "vitest";
import { AdminLoginLimiter, UploadRateLimiter } from "./rate-limit.js";

describe("rate limiters", () => {
  it("bounds tracked upload clients", () => {
    const limiter = new UploadRateLimiter("rate-limit-secret-with-more-than-24-characters");
    for (let attempt = 0; attempt < 8; attempt += 1) limiter.reserve("old-client", 0);
    expect(() => limiter.reserve("old-client", 0)).toThrow();
    for (let client = 0; client < 2_000; client += 1) limiter.reserve(`client-${client}`, 0);
    expect(() => limiter.reserve("old-client", 0)).not.toThrow();
  });

  it("bounds tracked administrator login clients", () => {
    const limiter = new AdminLoginLimiter();
    for (let attempt = 0; attempt < 10; attempt += 1) expect(limiter.allow("old-client", 0)).toBe(true);
    expect(limiter.allow("old-client", 0)).toBe(false);
    for (let client = 0; client < 2_000; client += 1) expect(limiter.allow(`client-${client}`, 0)).toBe(true);
    expect(limiter.allow("old-client", 0)).toBe(true);
  });
});
