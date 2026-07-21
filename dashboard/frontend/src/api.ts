import type { Flag, MarkKind } from "../../shared/vocab";
import type { Bar, Feedback, Mark, Run, Slices, Trade, TradeFilter, TradeSummary } from "./types";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  if (!res.ok) throw new ApiError(res.status, `${init?.method ?? "GET"} ${url} → ${res.status}`);
  return (await res.json()) as T;
}

const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "content-type": "application/json" },
  body: JSON.stringify(body),
});

/**
 * Fields the D1 schema allows to be NULL but which mean "this exported run is broken" when
 * missing — an export with a null entry price, direction, etc. should fail loudly rather than
 * render as a silent dash in the UI. `bars_json` is checked separately by `getTrade` since it
 * lives on `Trade`, not `TradeSummary`.
 */
const REQUIRED_TRADE_FIELDS: readonly (keyof TradeSummary)[] = [
  "entry_time",
  "exit_time",
  "direction",
  "entry_price",
  "exit_price",
  "stop_price",
  "target_price",
  "pnl",
  "net_pnl",
  "r_multiple",
  "is_win",
];

/** 502: the row came back as a successful (2xx) response, but its data is invalid/incomplete. */
const CORRUPT_ROW_STATUS = 502;

function assertTradeFields<T extends { id: string }>(trade: T, fields: readonly (keyof T)[]): T {
  for (const field of fields) {
    if (trade[field] === null || trade[field] === undefined) {
      throw new ApiError(
        CORRUPT_ROW_STATUS,
        `trade ${trade.id}: missing required field "${String(field)}"`
      );
    }
  }
  return trade;
}

export const getRuns = () => request<Run[]>("/api/runs");

export const getTrades = async (runId: string, filter: TradeFilter) => {
  const rows = await request<TradeSummary[]>(
    `/api/runs/${encodeURIComponent(runId)}/trades?filter=${filter}`
  );
  return rows.map((row) => assertTradeFields(row, REQUIRED_TRADE_FIELDS));
};

export const getTrade = async (id: string) => {
  const trade = await request<Trade>(`/api/trades/${encodeURIComponent(id)}`);
  return assertTradeFields(trade, [...REQUIRED_TRADE_FIELDS, "bars_json"]);
};

export const getSlices = (runId: string, feature: string, buckets = 4) =>
  request<Slices>(`/api/runs/${encodeURIComponent(runId)}/slices?feature=${feature}&buckets=${buckets}`);

export const getFeedback = () => request<Feedback[]>("/api/feedback");

export const postFeedback = (signal_key: string, flag: Flag) =>
  request<Feedback>("/api/feedback", json("POST", { signal_key, flag }));

export const getMarks = () => request<Mark[]>("/api/marks");

export const postMark = (signal_key: string, kind: MarkKind, bar_ts: string) =>
  request<Mark>("/api/marks", json("POST", { signal_key, kind, bar_ts }));

export const deleteMark = (signal_key: string, kind: MarkKind) =>
  request<{ deleted: number }>(
    `/api/marks?signal_key=${encodeURIComponent(signal_key)}&kind=${encodeURIComponent(kind)}`,
    { method: "DELETE" }
  );

/** Bars travel as a JSON string on the trade row; parse once per trade. */
export const getBars = (trade: Trade): Bar[] => JSON.parse(trade.bars_json) as Bar[];
