import type { D1Migration } from "@cloudflare/vitest-pool-workers";

// Test-only binding: the migrations read from ./migrations by vitest.config.ts.
declare global {
  namespace Cloudflare {
    interface Env {
      TEST_MIGRATIONS: D1Migration[];
    }
  }
}

export {};
