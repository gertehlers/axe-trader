import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import { seedRun } from "./seed";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

describe("runs api", () => {
  it("lists runs newest first", async () => {
    await seedRun(env.DB, { id: "r-old", created_at: "2026-07-01T00:00:00Z" });
    await seedRun(env.DB, { id: "r-new", created_at: "2026-07-19T00:00:00Z" });
    const res = await app.request("/api/runs", {}, env);
    expect(res.status).toBe(200);
    const rows = (await res.json()) as { id: string }[];
    expect(rows[0].id).toBe("r-new");
  });

  it("gets one run, 404 when missing", async () => {
    await seedRun(env.DB, { id: "r-1" });
    expect((await app.request("/api/runs/r-1", {}, env)).status).toBe(200);
    expect((await app.request("/api/runs/nope", {}, env)).status).toBe(404);
  });
});
