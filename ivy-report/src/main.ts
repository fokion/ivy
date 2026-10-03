import "./style.css";
import type { Filter } from "./model";
import { failures, header, suites, toolbar } from "./render";
import type { Report } from "./types";

async function load(): Promise<Report> {
  const text = document.getElementById("ivy-data")?.textContent?.trim() ?? "";
  if (text.startsWith("{")) return JSON.parse(text) as Report;
  if (import.meta.env.DEV) return (await import("./sample")).sample;
  throw new Error("the report holds no results");
}

function openAll(open: boolean) {
  document.querySelectorAll("details").forEach((d) => (d.open = open));
}

/** Opens the suite and test case of a #s1/t2 link, and scrolls to it. */
function reveal() {
  const id = decodeURIComponent(location.hash.slice(1));
  const target = id ? document.getElementById(id) : null;
  if (!target) return;
  let el: HTMLElement | null = target;
  while (el) {
    if (el instanceof HTMLDetailsElement) el.open = true;
    el = el.parentElement;
  }
  target.scrollIntoView({ block: "start" });
}

async function main() {
  const app = document.getElementById("app")!;
  let report: Report;
  try {
    report = await load();
  } catch (e) {
    app.textContent = String(e);
    return;
  }
  document.title = `Ivy test report — ${report.status === "FAIL" ? "failed" : "passed"}`;
  const filter: Filter = { status: "all", text: "", tag: "" };
  const content = document.createElement("div");
  const render = () => content.replaceChildren(suites(report, filter));
  const rerender = () => {
    app.replaceChildren(...[header(report), failures(report), toolbar(report, filter, rerender, openAll), content].filter((n): n is HTMLElement => n !== null));
    render();
  };
  rerender();
  reveal();
  window.addEventListener("hashchange", reveal);
  window.addEventListener("beforeprint", () => openAll(true));
}

void main();
