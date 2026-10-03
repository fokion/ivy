import type { Report, Status, StepResult, TestCase, TestSuite } from "./types";

/** "850 ms", "1.24 s", "2 min 05 s". */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "–";
  if (seconds < 1) return `${Math.round(seconds * 1000)} ms`;
  if (seconds < 60) return `${seconds.toFixed(2)} s`;
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds % 60);
  return `${m} min ${String(s).padStart(2, "0")} s`;
}

const IDENTIFIER = /^[A-Za-z_$][\w$]*$/;

/** The path of a member as written in expressions: `a.b`, `a["x-y"]` or `a[0]`. */
export function memberPath(parent: string, key: string | number): string {
  if (typeof key === "number") return `${parent}[${key}]`;
  if (IDENTIFIER.test(key)) return parent ? `${parent}.${key}` : key;
  return `${parent}[${JSON.stringify(key)}]`;
}

/**
 * Nested values as rows of (path, value), leaves only: `result.body.items[0].id`. At most
 * `limit` rows; the last row tells how many were left out.
 */
export function flattenPaths(value: unknown, limit = 2000): [string, unknown][] {
  const rows: [string, unknown][] = [];
  let skipped = 0;
  const walk = (v: unknown, path: string) => {
    if (rows.length >= limit) {
      skipped++;
      return;
    }
    if (Array.isArray(v)) {
      if (!v.length && path) rows.push([path, []]);
      v.forEach((item, i) => walk(item, memberPath(path, i)));
    } else if (v !== null && typeof v === "object") {
      const entries = Object.entries(v as Record<string, unknown>);
      if (!entries.length && path) rows.push([path, {}]);
      entries.forEach(([k, item]) => walk(item, memberPath(path, k)));
    } else if (path) {
      rows.push([path, v]);
    }
  };
  walk(value, "");
  if (skipped) rows.push([`… ${skipped} more values`, ""]);
  return rows;
}

/** The expression of an assertion: a string, or `{that|must: expression, with: {...}}`. */
export function assertionText(assertion: unknown): { text: string; must: boolean } {
  if (typeof assertion === "string") return { text: assertion, must: false };
  if (assertion && typeof assertion === "object") {
    const a = assertion as Record<string, unknown>;
    if (typeof a.that === "string") return { text: a.that, must: a.must === true };
    if (typeof a.must === "string") return { text: a.must, must: true };
  }
  return { text: JSON.stringify(assertion), must: false };
}

export interface Totals {
  suites: number;
  cases: number;
  passed: number;
  failed: number;
  skipped: number;
}

export function totals(report: Report): Totals {
  const t: Totals = { suites: report.test_suites.length, cases: 0, passed: 0, failed: 0, skipped: 0 };
  for (const s of report.test_suites) {
    for (const tc of s.testcases) {
      t.cases++;
      if (tc.status === "FAIL") t.failed++;
      else if (tc.status === "SKIP") t.skipped++;
      else t.passed++;
    }
  }
  return t;
}

export type StatusFilter = "all" | "FAIL" | "PASS" | "SKIP";

export interface Filter {
  status: StatusFilter;
  text: string;
  tag: string;
}

function caseText(tc: TestCase): string {
  const parts = [tc.name, tc.gherkin?.name ?? ""];
  for (const r of tc.results ?? []) {
    parts.push(r.name, ...(r.errors ?? []).map((e) => e.value));
  }
  return parts.join("\n").toLowerCase();
}

export function caseTags(suite: TestSuite, tc: TestCase): string[] {
  return tc.gherkin?.tags ?? suite.gherkin?.tags ?? [];
}

/** Whether a test case passes the filter: status, tag and free text (name, steps, failures). */
export function matches(suite: TestSuite, tc: TestCase, f: Filter): boolean {
  if (f.status !== "all" && normalize(tc.status) !== f.status) return false;
  if (f.tag && !caseTags(suite, tc).includes(f.tag)) return false;
  const q = f.text.trim().toLowerCase();
  return !(q && !(suite.name.toLowerCase().includes(q) || caseText(tc).includes(q)));

}

export function normalize(status: Status): "PASS" | "FAIL" | "SKIP" {
  return status === "FAIL" ? "FAIL" : status === "SKIP" ? "SKIP" : "PASS";
}

export function allTags(report: Report): string[] {
  const set = new Set<string>();
  for (const s of report.test_suites) {
    for (const tc of s.testcases) caseTags(s, tc).forEach((t) => set.add(t));
  }
  return [...set].sort();
}

/** The status of a Gherkin step from the ivy step it belongs to. */
export function gherkinStepStatus(tc: TestCase, stepIndex: number): "PASS" | "FAIL" | "SKIP" | "UNDEFINED" {
  if (stepIndex < 0) return "UNDEFINED";
  if (tc.gherkin?.problems?.length) return "SKIP";
  const r = (tc.results ?? []).find((x: StepResult) => x.number === stepIndex + 1);
  if (!r) return "SKIP";
  return normalize(r.status);
}

/** Width of a duration bar, relative to the longest one. */
export function barWidth(seconds: number, max: number): number {
  if (max <= 0) return 0;
  return Math.max(1, Math.round((seconds / max) * 100));
}

export interface CaseError {
  /** The step that failed, empty for problems of the test case itself. */
  step: string;
  message: string;
}

export function stepLabel(r: StepResult): string {
  return `#${r.number}${r.rangedEnable ? `-${r.rangedIndex}` : ""} ${r.name}`.trim();
}

/** Every error of a test case: Gherkin problems first, then step failures in order. */
export function caseErrors(tc: TestCase): CaseError[] {
  const errors: CaseError[] = (tc.gherkin?.problems ?? []).map((message) => ({ step: "", message }));
  for (const r of tc.results ?? []) {
    for (const e of r.errors ?? []) errors.push({ step: stepLabel(r), message: e.value });
  }
  return errors;
}

/** A screenshot of a step, with the link that opens it from the report. */
export interface Screenshot {
  name: string;
  href: string;
}

/**
 * The screenshots a step's result lists (`result.screenshots`, absolute paths), linked relative
 * to the directory of the report when they are inside it, so that the output directory can move.
 */
export function screenshots(r: StepResult, reportDir: string): Screenshot[] {
  const result = (r.computedVars?.result ?? null) as { screenshots?: unknown } | null;
  const paths = Array.isArray(result?.screenshots) ? result.screenshots.filter((p): p is string => typeof p === "string") : [];
  const dir = reportDir.replace(/\\/g, "/").replace(/\/+$/, "");
  return paths.map((p) => {
    const path = p.replace(/\\/g, "/");
    const name = path.slice(path.lastIndexOf("/") + 1);
    const relative = dir && path.startsWith(`${dir}/`) ? path.slice(dir.length + 1) : null;
    const href = relative !== null ? relative.split("/").map(encodeURIComponent).join("/") : `file://${encodeURI(path)}`;
    return { name, href };
  });
}
