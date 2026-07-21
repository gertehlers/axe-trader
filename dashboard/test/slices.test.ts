import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import { seedRun, seedTrade } from "./seed";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

type Slices = {
  baseline_win: number;
  trades: number;
  buckets: { lo: number; hi: number; count: number; win_pct: number; net_avg_pnl: number }[];
};

describe("slices api", () => {
  it("buckets by feature and reports conditional win%", async () => {
    const run = await seedRun(env.DB);
    // near-EMA winners, far-EMA losers
    for (let i = 0; i < 4; i++)
      await seedTrade(env.DB, run, {
        id: `near-${i}`,
        dist_to_trend_ema_atr: 0.5,
        is_win: 1,
        net_pnl: 1,
        signal_key: `US500|2025-03-0${i + 1}T14:30:00Z|LONG`,
      });
    for (let i = 0; i < 4; i++)
      await seedTrade(env.DB, run, {
        id: `far-${i}`,
        dist_to_trend_ema_atr: 5.0,
        is_win: 0,
        net_pnl: -2,
        signal_key: `US500|2025-04-0${i + 1}T14:30:00Z|LONG`,
      });

    const res = await app.request(
      `/api/runs/${run}/slices?feature=dist_to_trend_ema_atr&buckets=2`,
      {},
      env
    );
    expect(res.status).toBe(200);
    const body = (await res.json()) as Slices;
    expect(body.baseline_win).toBeCloseTo(0.5);
    expect(body.buckets[0].win_pct).toBeCloseTo(1.0); // near-EMA bucket
    expect(body.buckets[1].win_pct).toBeCloseTo(0.0); // far-EMA bucket
  });

  it("rejects a non-allowlisted feature", async () => {
    const run = await seedRun(env.DB);
    const res = await app.request(`/api/runs/${run}/slices?feature=id;DROP`, {}, env);
    expect(res.status).toBe(400);
  });
});
