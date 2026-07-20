import { env, applyD1Migrations } from "cloudflare:test";
import { describe, it, expect, beforeAll } from "vitest";
import app from "../src/index";
import { seedRun, seedTrade } from "./seed";

beforeAll(async () => {
  await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
});

describe("trades api", () => {
  it("lists trades without bars_json and filters losers", async () => {
    const run = await seedRun(env.DB);
    await seedTrade(env.DB, run, {
      id: "t-win",
      is_win: 1,
      signal_key: "US500|2025-03-04T14:30:00Z|LONG",
    });
    await seedTrade(env.DB, run, {
      id: "t-loss",
      is_win: 0,
      signal_key: "US500|2025-03-05T14:30:00Z|LONG",
    });

    const all = (await (
      await app.request(`/api/runs/${run}/trades`, {}, env)
    ).json()) as Record<string, unknown>[];
    expect(all.length).toBe(2);
    expect(all[0].bars_json).toBeUndefined();

    const losers = (await (
      await app.request(`/api/runs/${run}/trades?filter=losers`, {}, env)
    ).json()) as { id: string }[];
    expect(losers.map((t) => t.id)).toEqual(["t-loss"]);
  });

  it("returns one trade with bars_json", async () => {
    const run = await seedRun(env.DB);
    await seedTrade(env.DB, run, {
      id: "t-1",
      bars_json: '[{"t":1,"o":1,"h":2,"l":0,"c":1}]',
    });
    const res = await app.request("/api/trades/t-1", {}, env);
    expect(res.status).toBe(200);
    const row = (await res.json()) as { bars_json: string };
    expect(JSON.parse(row.bars_json)).toHaveLength(1);
    expect((await app.request("/api/trades/missing", {}, env)).status).toBe(404);
  });
});
