import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";

const remote = process.argv.includes("--remote");
const args = [
  "wrangler",
  "d1",
  "execute",
  "axe-trader-dashboard",
  remote ? "--remote" : "--local",
  "--json",
  "-y",
  "--command",
  "SELECT signal_key, flag, note, created_at FROM feedback ORDER BY created_at",
];
const raw = execFileSync("npx", args, { encoding: "utf8" });
// wrangler --json returns an array of statement results: [{ results: [...] }]
const parsed = JSON.parse(raw);
const rows = Array.isArray(parsed) ? (parsed[0]?.results ?? []) : (parsed.results ?? []);
const out = "../experiments/feedback.json";
writeFileSync(out, JSON.stringify(rows, null, 2));
console.log(`wrote ${rows.length} feedback rows -> experiments/feedback.json`);
