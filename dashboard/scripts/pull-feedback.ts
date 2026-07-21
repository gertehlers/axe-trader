import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";

const remote = process.argv.includes("--remote");

function query(sql: string): Record<string, unknown>[] {
  const args = [
    "wrangler",
    "d1",
    "execute",
    "axe-trader-dashboard",
    remote ? "--remote" : "--local",
    "--json",
    "-y",
    "--command",
    sql,
  ];
  const raw = execFileSync("npx", args, { encoding: "utf8" });
  // wrangler --json returns an array of statement results: [{ results: [...] }]
  const parsed = JSON.parse(raw);
  return Array.isArray(parsed) ? (parsed[0]?.results ?? []) : (parsed.results ?? []);
}

const feedback = query(
  "SELECT signal_key, flag, note, created_at FROM feedback ORDER BY created_at"
);
writeFileSync("../experiments/feedback.json", JSON.stringify(feedback, null, 2));
console.log(`wrote ${feedback.length} feedback rows -> experiments/feedback.json`);

const marks = query(
  "SELECT signal_key, kind, bar_ts, created_at FROM marks ORDER BY signal_key, kind"
);
writeFileSync("../experiments/marks.json", JSON.stringify(marks, null, 2));
console.log(`wrote ${marks.length} mark rows -> experiments/marks.json`);
