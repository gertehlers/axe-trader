/** The closed vocabularies shared by the Worker API and the phone UI. */

export const FLAGS = [
  "knife-catch",
  "chop",
  "news-spike",
  "late-entry",
  "bad-exit",
  "good-signal",
] as const;
export type Flag = (typeof FLAGS)[number];

export const MARK_KINDS = ["better-entry", "T1", "T2", "T3", "exit-here"] as const;
export type MarkKind = (typeof MARK_KINDS)[number];

export function isFlag(v: unknown): v is Flag {
  return typeof v === "string" && (FLAGS as readonly string[]).includes(v);
}

export function isMarkKind(v: unknown): v is MarkKind {
  return typeof v === "string" && (MARK_KINDS as readonly string[]).includes(v);
}
