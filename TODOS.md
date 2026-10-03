# TODOs

## Single-pass secret masking

- **What:** replace the per-form `contains`/`replace` loop in `Secrets.hide` (and the copy + sort of
  every form on each `addText`) with a single-pass matcher, such as Aho–Corasick built when the set
  of secrets changes.
- **Why:** the cost is lines × (secrets × ~9 encoded forms). A `range:` that marks 500 tokens secret
  means about 4,500 substring scans for every log line, console block and report value.
- **Pros:** masking cost stays flat as the number of secrets grows.
- **Cons:** a new algorithm in security-critical code. It must mask exactly the same text as today:
  the longest form wins, and every encoding in `Secrets.encodings` is covered.
- **Context:** the problem exists today and is not caused by parallel runs (`forms` is a `volatile`
  copy-on-write list, so concurrent reads are safe). Start by adding a secret-heavy variant to the
  `perfTest` benchmark to measure it, then compare old and new `hide` on a corpus of the encodings.
- **Depends on:** nothing.

## `--profile` per-phase step timings

- **What:** record render / run / assertions / set durations on each step result, print a per-run
  summary on the console, and show the phases in the HTML report's step timeline.
- **Why:** shows where a slow suite spends its time (templating vs connector vs assertions) without
  an external profiler.
- **Pros:** users can find slow steps themselves.
- **Cons:** touches `TestStepResult`, the console output in `Ivy`, `StepProcessor` timing points and
  the TypeScript report (`ivy-report/src/types.ts` and the timeline view).
- **Context:** cut from item 1.3 of the "performance, MCP server, Playwright" plan during its
  engineering review (2026-10-03), so Phase 1 could ship as suite-level parallelism only.
- **Depends on:** nothing. Easier after suite-level parallelism lands, because console output is
  then already grouped per test case.
