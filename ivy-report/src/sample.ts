// Example results for `npm run dev`; not part of the built report.
import type { Report } from "./types";

const now = new Date().toISOString();

const step = (number: number, name: string, status: "PASS" | "FAIL", duration: number, errors: string[] = []) => ({
  name,
  errors: errors.length ? errors.map((value) => ({ value })) : null,
  skipped: null,
  status,
  raw: `script: echo \${name}\nassertions:\n- result.exitCode == 0\n`,
  interpolated: `script: echo ${name}\n`,
  number,
  rangedIndex: 0,
  rangedEnable: false,
  inputVars: { name, base_url: "http://localhost:8080", "x-dashed": true },
  computedVars: {
    result: { stdout: name, stderr: "", exitCode: status === "PASS" ? 0 : 1, json: { items: [{ id: 1 }, { id: 2 }] } },
  },
  computedInfos: null,
  assertionsApplied: { ok: status === "PASS", assertions: [
      { assertion: "result.exitCode == 0", isOK: status === "PASS" },
      { assertion: { must: "result.json.items.length > 1" }, isOK: true },
    ] },
  retries: 0,
  stdout: name,
  stderr: "",
  duration,
  start: now,
  end: now,
});

export const sample: Report = {
  status: "FAIL",
  nbTestsuitesFail: 1,
  nbTestsuitesPass: 1,
  nbTestsuitesSkip: 0,
  duration: 3.42,
  start: now,
  end: now,
  test_suites: [
    {
      name: "Accounts",
      description: "Manage accounts over HTTP.",
      filepath: "features/accounts.feature",
      status: "FAIL",
      duration: 2.1,
      start: now,
      nbTestcasesFail: 1,
      nbTestcasesPass: 1,
      nbTestcasesSkip: 0,
      gherkin: { uri: "features/accounts.feature", keyword: "Feature", name: "Accounts", description: "", line: 2, tags: ["@api"] },
      testcases: [
        {
          name: "create-an-account",
          id: "features/accounts.feature:6",
          status: "PASS",
          duration: 0.8,
          skipped: null,
          results: [step(1, "When I POST /accounts", "PASS", 0.8)],
          gherkin: {
            keyword: "Scenario",
            name: "create an account",
            description: "",
            line: 6,
            tags: ["@api", "@smoke"],
            steps: [
              { keyword: "When ", text: 'I POST "/accounts"', line: 7, stepIndex: 0, createsStep: true },
              { keyword: "Then ", text: "the status is 201", line: 8, stepIndex: 0, createsStep: false },
            ],
            problems: [],
          },
        },
        {
          name: "delete-an-account",
          id: "features/accounts.feature:10",
          status: "FAIL",
          duration: 1.3,
          skipped: null,
          results: [
            step(1, "When I DELETE /accounts/1", "FAIL", 1.3, [
              'Testcase "delete an account", step #1 (features/accounts.feature:12): assertion failed: result.status == 204\n  result.status = 500',
            ]),
          ],
          gherkin: {
            keyword: "Scenario",
            name: "delete an account",
            description: "",
            line: 10,
            tags: ["@api"],
            steps: [
              { keyword: "When ", text: 'I DELETE "/accounts/1"', line: 11, stepIndex: 0, createsStep: true },
              { keyword: "Then ", text: "the status is 204", line: 12, stepIndex: 0, createsStep: false },
            ],
            problems: [],
          },
        },
      ],
    },
    {
      name: "shell",
      filepath: "suites/shell.yml",
      status: "PASS",
      duration: 1.32,
      start: now,
      nbTestcasesFail: 0,
      nbTestcasesPass: 1,
      nbTestcasesSkip: 0,
      testcases: [
        { name: "echo", id: "", status: "PASS", duration: 1.32, skipped: null, results: [step(1, "exec", "PASS", 1.32)] },
      ],
    },
  ],
};
