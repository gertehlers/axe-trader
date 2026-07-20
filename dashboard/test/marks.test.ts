import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import type { MarkRow } from "../src/schema";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

const post = (body: unknown) =>
  app.request(
    "/api/marks",
    { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) },
    env
  );

describe("marks api", () => {
  it("places a mark and moves it when the same kind is re-placed", async () => {
    const signal_key = "US500|2025-05-05T14:30:00Z|LONG";
    await post({ signal_key, kind: "T1", bar_ts: "2025-05-05T15:00:00Z" });
    const moved = await post({ signal_key, kind: "T1", bar_ts: "2025-05-05T15:30:00Z" });
    expect(moved.status).toBe(200);

    const rows = (await (
      await app.request(`/api/marks?signal_key=${encodeURIComponent(signal_key)}`, {}, env)
    ).json()) as MarkRow[];
    expect(rows).toHaveLength(1);
    expect(rows[0].bar_ts).toBe("2025-05-05T15:30:00Z");
  });

  it("keeps different kinds side by side and deletes one", async () => {
    const signal_key = "US500|2025-06-06T14:30:00Z|SHORT";
    await post({ signal_key, kind: "better-entry", bar_ts: "2025-06-06T14:00:00Z" });
    await post({ signal_key, kind: "exit-here", bar_ts: "2025-06-06T16:00:00Z" });

    const del = await app.request(
      `/api/marks?signal_key=${encodeURIComponent(signal_key)}&kind=exit-here`,
      { method: "DELETE" },
      env
    );
    expect(del.status).toBe(200);
    expect(await del.json()).toEqual({ deleted: 1 });

    const rows = (await (
      await app.request(`/api/marks?signal_key=${encodeURIComponent(signal_key)}`, {}, env)
    ).json()) as MarkRow[];
    expect(rows.map((r) => r.kind)).toEqual(["better-entry"]);
  });

  it("400s on an unknown kind or a missing field", async () => {
    const bad = await post({ signal_key: "US500|2025-07-07T14:30:00Z|LONG", kind: "T9", bar_ts: "x" });
    expect(bad.status).toBe(400);
    expect(await bad.json()).toEqual({ error: "unknown kind" });

    const missing = await post({ signal_key: "US500|2025-07-07T14:30:00Z|LONG", kind: "T1" });
    expect(missing.status).toBe(400);
  });
});
