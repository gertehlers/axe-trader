import { readFileSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { buildPushSql } from "./sql";

const runFile = process.argv[2] ?? "run.json";
const remote = process.argv.includes("--remote");
const file = JSON.parse(readFileSync(runFile, "utf8"));
const sql = buildPushSql(file);
const tmp = ".push.generated.sql";
writeFileSync(tmp, sql);

const args = [
  "d1",
  "execute",
  "axe-trader-dashboard",
  `--file=${tmp}`,
  remote ? "--remote" : "--local",
  "-y",
];
console.log(`wrangler ${args.join(" ")}`);
execFileSync("npx", ["wrangler", ...args], { stdio: "inherit" });
console.log(`pushed ${file.trades.length} trades for run ${file.run.id}`);
