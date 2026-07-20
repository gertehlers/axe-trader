import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

const insert = (id: string, key: string, kind: string, barTs: string) =>
  env.DB.prepare(
    "INSERT INTO marks (id, signal_key, kind, bar_ts, created_at) VALUES (?,?,?,?,?)"
  ).bind(id, key, kind, barTs, "2026-07-20T00:00:00Z").run();

describe("marks schema", () => {
  it("allows different kinds on the same trade", async () => {
    const key = "US500|2025-03-04T14:30:00Z|LONG";
    await insert("m1", key, "T1", "2025-03-04T15:00:00Z");
    await insert("m2", key, "T2", "2025-03-04T15:20:00Z");
    const { results } = await env.DB.prepare(
      "SELECT kind FROM marks WHERE signal_key = ? ORDER BY kind"
    ).bind(key).all<{ kind: string }>();
    expect(results.map((r) => r.kind)).toEqual(["T1", "T2"]);
  });

  it("rejects a second mark of the same kind on one trade", async () => {
    const key = "US500|2025-04-04T14:30:00Z|LONG";
    await insert("m3", key, "better-entry", "2025-04-04T14:00:00Z");
    await expect(insert("m4", key, "better-entry", "2025-04-04T14:05:00Z")).rejects.toThrow();
  });
});
