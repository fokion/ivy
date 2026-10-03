// The JSON written by ivy (Tests.toJson in ivy-core).

export type Status = "PASS" | "FAIL" | "SKIP" | "RUN" | "";

export interface Failure {
  value: string;
}

export interface AssertionApplied {
  assertion: unknown;
  isOK: boolean;
}

export interface StepResult {
  name: string;
  errors: Failure[] | null;
  skipped: Failure[] | null;
  status: Status;
  /** The step as written, as YAML. */
  raw: string | null;
  /** The step with its templates rendered, as YAML. */
  interpolated: string | null;
  number: number;
  rangedIndex: number;
  rangedEnable: boolean;
  /** The variables of the test case: nested values. */
  inputVars: Record<string, unknown> | null;
  /** `result` and the values the step set. */
  computedVars: Record<string, unknown> | null;
  computedInfos: string[] | null;
  assertionsApplied: { ok: boolean; assertions: AssertionApplied[] };
  retries: number;
  stdout: string;
  stderr: string;
  duration: number;
  start: string;
  end: string;
}

export interface GherkinStep {
  keyword: string;
  text: string;
  line: number;
  stepIndex: number;
  createsStep: boolean;
}

export interface GherkinScenario {
  keyword: string;
  name: string;
  description: string;
  line: number;
  tags: string[];
  steps: GherkinStep[];
  problems: string[];
}

export interface TestCase {
  name: string;
  id: string;
  status: Status;
  duration: number;
  skipped: Failure[] | null;
  results: StepResult[];
  gherkin?: GherkinScenario;
}

export interface GherkinFeature {
  uri: string;
  keyword: string;
  name: string;
  description: string;
  line: number;
  tags: string[];
}

export interface TestSuite {
  name: string;
  description?: string;
  testcases: TestCase[];
  filepath: string;
  status: Status;
  duration: number;
  start: string;
  nbTestcasesFail: number;
  nbTestcasesPass: number;
  nbTestcasesSkip: number;
  gherkin?: GherkinFeature;
}

export interface Report {
  test_suites: TestSuite[];
  status: Status;
  nbTestsuitesFail: number;
  nbTestsuitesPass: number;
  nbTestsuitesSkip: number;
  duration: number;
  start: string;
  end: string;
}
