import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import { seedRun, seedTrade } from "./seed";
import type { FeedbackRow } from "../src/schema";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

const post = (body: unknown) =>
  app.request(
    "/api/feedback",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    },
    env
  );

describe("feedback api", () => {
  it("upserts by signal_key (second post updates, not duplicates)", async () => {
    const key = "US500|2025-03-04T14:30:00Z|LONG";
    await post({ signal_key: key, flag: "hypothesis", note: "first" });
    const second = await post({ signal_key: key, flag: "knife-catch", note: "second" });
    expect(second.status).toBe(200);
    const all = (await (await app.request("/api/feedback", {}, env)).json()) as FeedbackRow[];
    const forKey = all.filter((f) => f.signal_key === key);
    expect(forKey).toHaveLength(1);
    expect(forKey[0].note).toBe("second");
  });

  it("re-attaches across runs via signal_key", async () => {
    const key = "US500|2025-06-06T14:30:00Z|LONG";
    await post({ signal_key: key, flag: "hypothesis", note: "flagged in run A" });
    // simulate a later backtest: a NEW run + NEW trade id, SAME signal_key
    const runB = await seedRun(env.DB, { id: "run-B" });
    await seedTrade(env.DB, runB, { id: "trade-B", signal_key: key });
    const fb = (await (
      await app.request(`/api/feedback?signal_key=${encodeURIComponent(key)}`, {}, env)
    ).json()) as FeedbackRow;
    expect(fb.note).toBe("flagged in run A");
  });

  it("400 on missing signal_key", async () => {
    expect((await post({ flag: "x" })).status).toBe(400);
  });
});
