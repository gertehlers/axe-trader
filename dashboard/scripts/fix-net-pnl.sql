-- One-off repair for runs exported BEFORE commit 2cd6944.
--
-- DashboardExporter used to write `n.put("net_pnl", t.pnl())` -- raw pnl under a column named
-- net_pnl -- while the run-level `net_avg_pnl` was genuinely net (mean of pnl - avgSpread).
-- The dashboard's equity curve cumulates trades.net_pnl under an "Equity (cumulative net pts)"
-- heading, so any run exported before that commit draws a curve that disagrees with the
-- net-expectancy KPI tile directly above it.
--
-- Evidence the run needs this (before running): AVG(pnl) and AVG(net_pnl) are byte-identical.
--   SELECT AVG(pnl), AVG(net_pnl) FROM trades WHERE run_id = 'US500-1784554730790';
--   -- both 1.334296674469148 -> net_pnl is holding gross
--
-- avgSpread is not stored anywhere, but it is exactly recoverable, because
--   net_avg_pnl = mean(pnl) - avgSpread   =>   avgSpread = mean(pnl) - net_avg_pnl
-- For US500-1784554730790 that is 1.334296674469148 - 0.8548731320570223 = 0.4794235424121257.
-- The statement below derives it inline rather than hardcoding it, so it cannot be mistranscribed.
--
-- Reversible: `UPDATE trades SET net_pnl = pnl WHERE run_id = '...'` restores the prior state.
-- Idempotent it is NOT -- running it twice subtracts the spread twice. Check with the SELECT above
-- first; if AVG(pnl) and AVG(net_pnl) already differ by the spread, it has already been applied.
--
-- Run with:
--   cd dashboard
--   npx wrangler d1 execute axe-trader-dashboard --remote --file scripts/fix-net-pnl.sql
-- (drop --remote, or use --local, to repair the local dev copy instead)

UPDATE trades
SET net_pnl = pnl - (
      (SELECT AVG(pnl) FROM trades WHERE run_id = 'US500-1784554730790')
    - (SELECT net_avg_pnl FROM runs WHERE id = 'US500-1784554730790')
  )
WHERE run_id = 'US500-1784554730790';
