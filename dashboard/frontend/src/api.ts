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

export const getRuns = () => request<Run[]>("/api/runs");

export const getTrades = (runId: string, filter: TradeFilter) =>
  request<TradeSummary[]>(`/api/runs/${runId}/trades?filter=${filter}`);

export const getTrade = (id: string) => request<Trade>(`/api/trades/${id}`);

export const getSlices = (runId: string, feature: string, buckets = 4) =>
  request<Slices>(`/api/runs/${runId}/slices?feature=${feature}&buckets=${buckets}`);

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
