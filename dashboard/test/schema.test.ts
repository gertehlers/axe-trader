import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

describe("schema", () => {
  it("has runs, trades, feedback tables", async () => {
    const { results } = await env.DB.prepare(
      "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    ).all<{ name: string }>();
    const names = results.map((r) => r.name);
    expect(names).toContain("runs");
    expect(names).toContain("trades");
    expect(names).toContain("feedback");
  });

  it("enforces one feedback row per signal_key", async () => {
    await env.DB.prepare(
      "INSERT INTO feedback (id, signal_key, flag, note, created_at) VALUES (?,?,?,?,?)"
    )
      .bind("f1", "US500|2025-01-01T00:00:00Z|LONG", "hypothesis", "n", "2026-07-19T00:00:00Z")
      .run();
    await expect(
      env.DB.prepare(
        "INSERT INTO feedback (id, signal_key, flag, note, created_at) VALUES (?,?,?,?,?)"
      )
        .bind("f2", "US500|2025-01-01T00:00:00Z|LONG", "x", "y", "2026-07-19T00:00:01Z")
        .run()
    ).rejects.toThrow();
  });
});
