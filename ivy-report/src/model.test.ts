import { describe, expect, it } from "vitest";
import { assertionText, barWidth, caseErrors, flattenPaths, formatDuration, gherkinStepStatus, matches, screenshots, totals } from "./model";
import type { Report, TestCase, TestSuite } from "./types";

const step = (number: number, status: "PASS" | "FAIL" | "SKIP") =>
  ({ name: "s", number, status, errors: status === "FAIL" ? [{ value: "boom" }] : null }) as never;

const tc: TestCase = {
  name: "login",
  id: "",
  status: "FAIL",
  duration: 1,
  skipped: null,
  results: [step(1, "PASS"), step(2, "FAIL")],
  gherkin: { keyword: "Scenario", name: "login", description: "", line: 3, tags: ["@smoke"], steps: [], problems: [] },
};
const suite = { name: "auth", testcases: [tc] } as unknown as TestSuite;

describe("model", () => {
  it("formats durations", () => {
    expect(formatDuration(0.25)).toBe("250 ms");
    expect(formatDuration(1.234)).toBe("1.23 s");
    expect(formatDuration(125)).toBe("2 min 05 s");
  });

  it("lists nested values by their expression paths", () => {
    expect(flattenPaths({ result: { body: { items: [{ id: 1 }], "x-y": "a" }, empty: [] }, n: null })).toEqual([
      ["result.body.items[0].id", 1],
      ['result.body["x-y"]', "a"],
      ["result.empty", []],
      ["n", null],
    ]);
    expect(flattenPaths({ a: [1, 2, 3] }, 2)).toEqual([
      ["a[0]", 1],
      ["a[1]", 2],
      ["… 1 more values", ""],
    ]);
  });

  it("reads assertions", () => {
    expect(assertionText("result.status == 200")).toEqual({ text: "result.status == 200", must: false });
    expect(assertionText({ must: "a > 1" })).toEqual({ text: "a > 1", must: true });
    expect(assertionText({ that: "a == arg1", with: { arg1: 2 } })).toEqual({ text: "a == arg1", must: false });
  });

  it("counts test cases", () => {
    const report = { test_suites: [suite] } as unknown as Report;
    expect(totals(report)).toEqual({ suites: 1, cases: 1, passed: 0, failed: 1, skipped: 0 });
  });

  it("filters by status, tag and text", () => {
    expect(matches(suite, tc, { status: "FAIL", text: "", tag: "" })).toBe(true);
    expect(matches(suite, tc, { status: "PASS", text: "", tag: "" })).toBe(false);
    expect(matches(suite, tc, { status: "all", text: "boom", tag: "@smoke" })).toBe(true);
    expect(matches(suite, tc, { status: "all", text: "", tag: "@slow" })).toBe(false);
  });

  it("derives Gherkin step statuses", () => {
    expect(gherkinStepStatus(tc, 1)).toBe("FAIL");
    expect(gherkinStepStatus(tc, 0)).toBe("PASS");
    expect(gherkinStepStatus(tc, 5)).toBe("SKIP");
    expect(gherkinStepStatus(tc, -1)).toBe("UNDEFINED");
  });

  it("collects the errors of a test case", () => {
    expect(caseErrors(tc)).toEqual([{ step: "#2 s", message: "boom" }]);
    const undefinedStep = { ...tc, results: [], gherkin: { ...tc.gherkin!, problems: ["undefined step"] } };
    expect(caseErrors(undefinedStep)).toEqual([{ step: "", message: "undefined step" }]);
  });

  it("sizes duration bars", () => {
    expect(barWidth(1, 4)).toBe(25);
    expect(barWidth(0, 4)).toBe(1);
    expect(barWidth(1, 0)).toBe(0);
  });

  it("links screenshots relative to the report when they are inside its directory", () => {
    const r = {
      computedVars: { result: { screenshots: ["/runs/out/shots/home page.png", "/elsewhere/x.png"] } },
    } as never;
    expect(screenshots(r, "/runs/out")).toEqual([
      { name: "home page.png", href: "shots/home%20page.png" },
      { name: "x.png", href: "file:///elsewhere/x.png" },
    ]);
    expect(screenshots({ computedVars: { result: { stdout: "" } } } as never, "/runs/out")).toEqual([]);
    expect(screenshots({ computedVars: null } as never, "")).toEqual([]);
  });
});
