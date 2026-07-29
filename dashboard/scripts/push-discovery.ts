import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { buildDiscoveryPushSql } from "./discovery-sql";

const reportFile = process.argv[2] ?? "discovery-report.json";
const remote = process.argv.includes("--remote");
const report = JSON.parse(readFileSync(reportFile, "utf8"));
const sqlFile = ".push.discovery.generated.sql";
writeFileSync(sqlFile, buildDiscoveryPushSql(report));
execFileSync("npx", ["wrangler", "d1", "execute", "axe-trader-dashboard", `--file=${sqlFile}`, remote ? "--remote" : "--local", "-y"], { stdio: "inherit" });
console.log(`pushed discovery report ${report.run.run_key}`);
