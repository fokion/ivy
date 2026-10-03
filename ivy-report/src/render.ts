import { h } from "./dom";
import {
  allTags,
  barWidth,
  type CaseError,
  caseErrors,
  caseTags,
  assertionText,
  flattenPaths,
  type Filter,
  formatDuration,
  gherkinStepStatus,
  matches,
  normalize,
  stepLabel,
  totals,
  screenshots,
} from "./model";
import type { Report, StepResult, TestCase, TestSuite } from "./types";

const STATUS_STYLE: Record<string, string> = {
  PASS: "bg-emerald-100 text-emerald-800 ring-emerald-600/20 dark:bg-emerald-500/15 dark:text-emerald-300",
  FAIL: "bg-rose-100 text-rose-800 ring-rose-600/20 dark:bg-rose-500/15 dark:text-rose-300",
  SKIP: "bg-slate-100 text-slate-700 ring-slate-500/20 dark:bg-slate-500/15 dark:text-slate-300",
  UNDEFINED: "bg-amber-100 text-amber-800 ring-amber-600/20 dark:bg-amber-500/15 dark:text-amber-300",
};

const BAR_STYLE: Record<string, string> = {
  PASS: "bg-emerald-500",
  FAIL: "bg-rose-500",
  SKIP: "bg-slate-400",
};

function badge(status: string) {
  const label = status === "PASS" ? "passed" : status === "FAIL" ? "failed" : status === "SKIP" ? "skipped" : status.toLowerCase();
  return h(
    "span",
    { class: `inline-flex shrink-0 items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${STATUS_STYLE[status] ?? STATUS_STYLE.SKIP}` },
    label,
  );
}

function chevron() {
  return h("span", { class: "chevron inline-block w-3 text-slate-400", "aria-hidden": "true" }, "▸");
}

function tag(name: string) {
  return h("span", { class: "rounded bg-indigo-50 px-1.5 py-0.5 font-mono text-[11px] text-indigo-700 dark:bg-indigo-500/15 dark:text-indigo-300" }, name);
}

function bar(seconds: number, max: number, status: string) {
  return h(
    "div",
    { class: "hidden h-1.5 w-32 overflow-hidden rounded-full bg-slate-200 sm:block dark:bg-slate-800", title: formatDuration(seconds) },
    h("div", { class: `h-full ${BAR_STYLE[status] ?? BAR_STYLE.SKIP}`, style: `width:${barWidth(seconds, max)}%` }),
  );
}

function stat(label: string, value: string | number, accent = "") {
  return h(
    "div",
    { class: "rounded-xl bg-white p-4 text-slate-900 shadow-sm ring-1 ring-slate-200 dark:bg-slate-900 dark:text-slate-100 dark:ring-slate-800" },
    h("div", { class: "text-xs font-medium uppercase tracking-wide text-slate-500" }, label),
    h("div", { class: `mt-1 text-2xl font-semibold tabular-nums ${accent}` }, value),
  );
}

export function header(report: Report) {
  const t = totals(report);
  const start = report.start ? new Date(report.start) : null;
  return h(
    "header",
    { class: "mx-auto max-w-6xl px-4 pt-8 sm:px-6" },
    h(
      "div",
      { class: "flex flex-wrap items-center gap-3" },
      h("h1", { class: "text-2xl font-semibold tracking-tight" }, "Ivy test report"),
      badge(normalize(report.status)),
      h("span", { class: "text-sm text-slate-500" }, start && !Number.isNaN(start.getTime()) ? `started ${start.toLocaleString()}` : ""),
    ),
    h(
      "div",
      { class: "mt-6 grid grid-cols-2 gap-3 sm:grid-cols-5" },
      stat("Suites", t.suites),
      stat("Test cases", t.cases),
      stat("Passed", t.passed, "text-emerald-600 dark:text-emerald-400"),
      stat("Failed", t.failed, t.failed ? "text-rose-600 dark:text-rose-400" : ""),
      stat("Duration", formatDuration(report.duration)),
    ),
  );
}

export function toolbar(report: Report, filter: Filter, onChange: () => void, onExpand: (open: boolean) => void) {
  const button = (label: string, value: Filter["status"]) =>
    h(
      "button",
      {
        type: "button",
        class: `rounded-md px-3 py-1.5 text-sm font-medium ${filter.status === value ? "bg-slate-900 text-white dark:bg-white dark:text-slate-900" : "text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"}`,
        onclick: () => {
          filter.status = value;
          onChange();
        },
      },
      label,
    );
  const tags = allTags(report);
  const search = h("input", {
    type: "search",
    placeholder: "Search test cases, steps, failures…",
    value: filter.text,
    class: "w-full rounded-lg border-0 bg-white px-3 py-2 text-sm shadow-sm ring-1 ring-slate-200 placeholder:text-slate-400 focus:ring-2 focus:ring-indigo-500 sm:w-80 dark:bg-slate-900 dark:ring-slate-700",
    oninput: (e: Event) => {
      filter.text = (e.target as HTMLInputElement).value;
      onChange();
    },
  });
  const tagSelect = tags.length
    ? h(
        "select",
        {
          class: "rounded-lg border-0 bg-white py-2 pr-8 pl-3 text-sm shadow-sm ring-1 ring-slate-200 dark:bg-slate-900 dark:ring-slate-700",
          onchange: (e: Event) => {
            filter.tag = (e.target as HTMLSelectElement).value;
            onChange();
          },
        },
        h("option", { value: "" }, "All tags"),
        tags.map((t) => h("option", { value: t, selected: filter.tag === t }, t)),
      )
    : null;
  const small = "rounded-md px-2 py-1.5 text-sm text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800";
  return h(
    "div",
    { class: "no-print sticky top-0 z-10 mt-6 border-b border-slate-200 bg-slate-50/90 backdrop-blur dark:border-slate-800 dark:bg-slate-950/90" },
    h(
      "div",
      { class: "mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-3 sm:px-6" },
      search,
      h(
        "div",
        { class: "flex rounded-lg bg-white p-1 shadow-sm ring-1 ring-slate-200 dark:bg-slate-900 dark:ring-slate-700" },
        button("All", "all"),
        button("Failed", "FAIL"),
        button("Passed", "PASS"),
        button("Skipped", "SKIP"),
      ),
      tagSelect,
      h(
        "div",
        { class: "ml-auto flex gap-1" },
        h("button", { type: "button", class: small, onclick: () => onExpand(true) }, "Expand all"),
        h("button", { type: "button", class: small, onclick: () => onExpand(false) }, "Collapse all"),
      ),
    ),
  );
}

function pre(text: string) {
  return h(
    "pre",
    { class: "max-h-96 overflow-auto rounded-lg bg-slate-900 p-3 font-mono text-xs leading-relaxed text-slate-100 dark:bg-black/60" },
    text,
  );
}

function varsTable(vars: Record<string, unknown> | null) {
  const entries = flattenPaths(vars ?? {});
  if (!entries.length) return h("p", { class: "text-sm text-slate-500" }, "No variables.");
  return h(
    "div",
    { class: "max-h-96 overflow-auto rounded-lg ring-1 ring-slate-200 dark:ring-slate-800" },
    h(
      "table",
      { class: "w-full text-left font-mono text-xs" },
      h(
        "tbody",
        { class: "divide-y divide-slate-100 dark:divide-slate-800" },
        entries.map(([k, v]) =>
          h(
            "tr",
            {},
            h("td", { class: "w-1/3 px-3 py-1.5 align-top text-slate-500" }, k),
            h("td", { class: "px-3 py-1.5 break-all" }, typeof v === "string" ? v : JSON.stringify(v)),
          ),
        ),
      ),
    ),
  );
}

/** Tabs over the details of a step; panels are built when first shown. */
function tabs(panels: [string, () => Node][]) {
  const body = h("div", { class: "mt-3" });
  const buttons: HTMLButtonElement[] = [];
  const show = (i: number) => {
    buttons.forEach((b, j) => {
      b.className = `border-b-2 px-3 py-1.5 text-sm ${i === j ? "border-indigo-500 font-medium text-indigo-600 dark:text-indigo-400" : "border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300"}`;
    });
    body.replaceChildren(panels[i][1]());
  };
  panels.forEach(([label], i) => buttons.push(h("button", { type: "button", onclick: () => show(i) }, label)));
  const nav = h("div", { class: "flex flex-wrap gap-1 border-b border-slate-200 dark:border-slate-800" }, buttons);
  show(0);
  return h("div", {}, nav, body);
}

function assertionsPanel(r: StepResult) {
  const list = r.assertionsApplied?.assertions ?? [];
  if (!list.length) return h("p", { class: "text-sm text-slate-500" }, "No assertions.");
  return h(
    "ul",
    { class: "space-y-1 font-mono text-xs" },
    list.map((a) => {
      const { text, must } = assertionText(a.assertion);
      return h(
        "li",
        { class: "flex items-start gap-2" },
        h("span", { class: a.isOK ? "text-emerald-600" : "text-rose-600" }, a.isOK ? "✓" : "✗"),
        must
          ? h("span", { class: "rounded bg-amber-100 px-1 text-[10px] font-semibold text-amber-800 dark:bg-amber-500/15 dark:text-amber-300" }, "must")
          : null,
        h("span", { class: "break-all" }, text),
      );
    }),
  );
}

/** The directory of the report file, to link screenshots relative to it. */
function reportDir(): string {
  if (location.protocol !== "file:") return "";
  const path = decodeURIComponent(location.pathname);
  return path.slice(0, path.lastIndexOf("/"));
}

function screenshotsPanel(shots: { name: string; href: string }[]) {
  return h(
    "div",
    { class: "grid grid-cols-2 gap-3 sm:grid-cols-3" },
    shots.map((s) =>
      h(
        "a",
        { href: s.href, target: "_blank", rel: "noopener", class: "block text-xs text-slate-500 hover:text-indigo-600" },
        h("img", { src: s.href, alt: s.name, loading: "lazy", class: "mb-1 max-h-48 w-full rounded object-contain ring-1 ring-slate-200 dark:ring-slate-800" }),
        s.name,
      ),
    ),
  );
}

function stepView(r: StepResult, maxDuration: number) {
  const status = normalize(r.status);
  const shots = screenshots(r, reportDir());
  const errors = r.errors ?? [];
  const output = [r.stdout?.trim() ? `stdout:\n${r.stdout.trim()}` : "", r.stderr?.trim() ? `stderr:\n${r.stderr.trim()}` : ""]
    .filter(Boolean)
    .join("\n\n");
  return h(
    "details",
    { class: "group rounded-lg ring-1 ring-slate-200 dark:ring-slate-800", open: status === "FAIL" },
    h(
      "summary",
      { class: "flex cursor-pointer items-center gap-3 px-3 py-2 text-sm" },
      chevron(),
      badge(status),
      h("span", { class: "min-w-0 flex-1 truncate font-medium" }, stepLabel(r)),
      r.retries > 0 ? h("span", { class: "text-xs text-amber-600" }, `${r.retries} retries`) : null,
      bar(r.duration, maxDuration, status),
      h("span", { class: "w-20 text-right text-xs text-slate-500 tabular-nums" }, formatDuration(r.duration)),
    ),
    h(
      "div",
      { class: "border-t border-slate-200 px-3 py-3 dark:border-slate-800" },
      errors.length
        ? h(
            "div",
            { class: "mb-3 space-y-1 rounded-lg bg-rose-50 p-3 text-sm text-rose-800 dark:bg-rose-500/10 dark:text-rose-300" },
            errors.map((e) => h("p", { class: "font-mono text-xs break-words whitespace-pre-wrap" }, e.value)),
          )
        : null,
      (r.computedInfos ?? []).length
        ? h("ul", { class: "mb-3 space-y-1 text-sm text-sky-700 dark:text-sky-300" }, (r.computedInfos ?? []).map((i) => h("li", {}, `ℹ ${i}`)))
        : null,
      tabs([
        ["Assertions", () => assertionsPanel(r)],
        ["Output", () => (output ? pre(output) : h("p", { class: "text-sm text-slate-500" }, "No output."))],
        ["Step", () => pre(r.raw ?? "")],
        ["Interpolated", () => pre(r.interpolated ?? "")],
        ["Result variables", () => varsTable(r.computedVars)],
        ["Input variables", () => varsTable(r.inputVars)],
        ...(shots.length ? [["Screenshots", () => screenshotsPanel(shots)] as [string, () => Node]] : []),
      ]),
    ),
  );
}

function gherkinView(tc: TestCase) {
  const g = tc.gherkin!;
  return h(
    "div",
    { class: "mb-3 rounded-lg bg-slate-50 p-3 font-mono text-xs dark:bg-slate-900/60" },
    g.steps.map((s) => {
      const st = gherkinStepStatus(tc, s.stepIndex);
      const color =
        st === "PASS" ? "text-emerald-600" : st === "FAIL" ? "text-rose-600" : st === "UNDEFINED" ? "text-amber-600" : "text-slate-400";
      return h(
        "div",
        { class: "flex gap-2 py-0.5" },
        h("span", { class: `w-4 ${color}` }, st === "PASS" ? "✓" : st === "FAIL" ? "✗" : st === "UNDEFINED" ? "?" : "–"),
        h("span", { class: "font-semibold text-indigo-600 dark:text-indigo-400" }, s.keyword.trim()),
        h("span", { class: "flex-1" }, s.text),
        h("span", { class: "text-slate-400" }, `:${s.line}`),
      );
    }),
    g.problems.length
      ? h("div", { class: "mt-2 space-y-2 text-amber-700 dark:text-amber-300" }, g.problems.map((p) => h("pre", { class: "whitespace-pre-wrap" }, p)))
      : null,
  );
}

const MAX_SUMMARY_ERRORS = 3;

function errorLine(e: CaseError) {
  return h(
    "li",
    { class: "font-mono text-xs break-words whitespace-pre-wrap text-rose-700 dark:text-rose-300" },
    e.step ? h("span", { class: "font-semibold" }, `${e.step}: `) : null,
    e.message.trim(),
  );
}

/** The first errors of a test case, shown without expanding it. */
function errorSummary(errors: CaseError[]) {
  if (!errors.length) return null;
  const more = errors.length - MAX_SUMMARY_ERRORS;
  return h(
    "ul",
    { class: "mt-2 space-y-1 rounded-lg bg-rose-50 p-2 dark:bg-rose-500/10" },
    errors.slice(0, MAX_SUMMARY_ERRORS).map(errorLine),
    more > 0 ? h("li", { class: "text-xs text-rose-600 dark:text-rose-400" }, `+ ${more} more`) : null,
  );
}

/** Every failed test case with its errors, linking to the test case. */
export function failures(report: Report) {
  const rows = report.test_suites.flatMap((suite, si) =>
    suite.testcases
      .map((tc, ti) => ({ tc, id: `s${si}/t${ti}`, errors: caseErrors(tc) }))
      .filter(({ tc }) => normalize(tc.status) === "FAIL")
      .map(({ tc, id, errors }) =>
        h(
          "li",
          { class: "px-4 py-3" },
          h(
            "a",
            { href: `#${id}`, class: "font-medium text-rose-700 hover:underline dark:text-rose-300" },
            `${suite.name} › ${tc.gherkin ? tc.gherkin.name : tc.name}`,
          ),
          h("span", { class: "ml-2 font-mono text-xs text-slate-500" }, suite.filepath),
          errors.length
            ? h("ul", { class: "mt-1 space-y-1" }, errors.map(errorLine))
            : h("p", { class: "text-xs text-slate-500" }, "Failed without an error message."),
        ),
      ),
  );
  if (!rows.length) return null;
  return h(
    "section",
    { class: "mx-auto mt-6 max-w-6xl px-4 sm:px-6" },
    h(
      "details",
      {
        class: "rounded-xl bg-white text-slate-900 shadow-sm ring-1 ring-rose-200 dark:bg-slate-900 dark:text-slate-100 dark:ring-rose-500/30",
        open: true,
      },
      h(
        "summary",
        { class: "flex cursor-pointer items-center gap-3 px-4 py-3" },
        chevron(),
        h("h2", { class: "font-semibold text-rose-700 dark:text-rose-300" }, `Failures (${rows.length})`),
      ),
      h("ul", { class: "divide-y divide-slate-100 border-t border-slate-200 dark:divide-slate-800 dark:border-slate-800" }, rows),
    ),
  );
}

function caseView(suite: TestSuite, tc: TestCase, id: string, maxDuration: number) {
  const status = normalize(tc.status);
  const steps = tc.results ?? [];
  const stepMax = Math.max(0, ...steps.map((r) => r.duration));
  const title = tc.gherkin ? `${tc.gherkin.keyword}: ${tc.gherkin.name}` : tc.name;
  return h(
    "details",
    { id, class: "case rounded-xl bg-white text-slate-900 shadow-sm ring-1 ring-slate-200 dark:bg-slate-900 dark:text-slate-100 dark:ring-slate-800", open: status === "FAIL" },
    h(
      "summary",
      { class: "flex cursor-pointer items-center gap-3 px-4 py-3" },
      chevron(),
      badge(status),
      h(
        "div",
        { class: "min-w-0 flex-1" },
        h("div", { class: "truncate font-medium" }, title),
        caseTags(suite, tc).length ? h("div", { class: "mt-1 flex flex-wrap gap-1" }, caseTags(suite, tc).map(tag)) : null,
        errorSummary(caseErrors(tc)),
      ),
      h("a", { href: `#${id}`, class: "no-print text-xs text-slate-400 hover:text-indigo-500", title: "Link to this test case" }, "#"),
      bar(tc.duration, maxDuration, status),
      h("span", { class: "w-20 text-right text-xs text-slate-500 tabular-nums" }, formatDuration(tc.duration)),
    ),
    h(
      "div",
      { class: "space-y-2 border-t border-slate-200 px-4 py-3 dark:border-slate-800" },
      tc.gherkin ? gherkinView(tc) : null,
      (tc.skipped ?? []).map((s) => h("p", { class: "text-sm text-slate-500" }, s.value)),
      steps.length ? steps.map((r) => stepView(r, stepMax)) : h("p", { class: "text-sm text-slate-500" }, "No step ran."),
    ),
  );
}

export function suites(report: Report, filter: Filter) {
  const maxCase = Math.max(0, ...report.test_suites.flatMap((s) => s.testcases.map((tc) => tc.duration)));
  const views = report.test_suites.map((suite, si) => {
    const cases = suite.testcases
      .map((tc, ti) => ({ tc, ti }))
      .filter(({ tc }) => matches(suite, tc, filter));
    if (!cases.length) return null;
    const status = normalize(suite.status);
    return h(
      "details",
      { id: `s${si}`, class: "suite", open: true },
      h(
        "summary",
        { class: "flex cursor-pointer items-center gap-3 py-2" },
        chevron(),
        badge(status),
        h(
          "div",
          { class: "min-w-0 flex-1" },
          h("h2", { class: "truncate text-lg font-semibold" }, suite.gherkin ? `${suite.gherkin.keyword}: ${suite.name}` : suite.name),
          h("p", { class: "truncate font-mono text-xs text-slate-500" }, suite.filepath),
        ),
        h(
          "span",
          { class: "text-sm text-slate-500 tabular-nums" },
          `${suite.nbTestcasesPass} passed · ${suite.nbTestcasesFail} failed · ${suite.nbTestcasesSkip} skipped · ${formatDuration(suite.duration)}`,
        ),
      ),
      suite.description ? h("p", { class: "mb-2 ml-6 text-sm whitespace-pre-line text-slate-600 dark:text-slate-400" }, suite.description) : null,
      h("div", { class: "ml-0 space-y-2 sm:ml-6" }, cases.map(({ tc, ti }) => caseView(suite, tc, `s${si}/t${ti}`, maxCase))),
    );
  });
  const visible = views.filter(Boolean);
  return h(
    "main",
    { class: "mx-auto max-w-6xl space-y-6 px-4 py-6 sm:px-6" },
    visible.length ? visible : h("p", { class: "py-12 text-center text-slate-500" }, "No test case matches the filters."),
  );
}
