# Operator quickstart

Every step below was actually executed on 2026-08-26 (babashka 1.12.218,
macOS) against commit `eb85de3`. Observed outputs are quoted as measured — if a
step stops matching, the repo has drifted, not this page.

## What you are operating

This repo is the **recap** media helper app: LangGraph graphs under
`lg-clj/src/lg_recap/graphs/` (health, download, get_info, list_downloads,
summarize) wired through `lg-recap.server` dispatch. The smoke suite exercises
graph wiring and pure helpers **without** live yt-dlp, B2 upload, or an LLM —
credentials and network are not required for the path documented here.

What is **not** verified by this quickstart: deploying the cljs UI (`cljs/`,
migrated off Svelte), running the Cloudflare Worker config (`wrangler.jsonc`),
or end-to-end download against a real URL. Those need operator credentials and
are out of scope for a no-network gate.

## Prerequisites

- [babashka](https://babashka.org/) on `PATH` (measured: `babashka v1.12.208`)
- Network access to github.com on first run — `lg-clj/bb.edn` pulls
  `langchain-clj` and `langgraph-clj` as git deps

## Steps

```bash
git clone git@github.com:cloud-itonami/recap.git
cd recap/lg-clj
bb test
```

Measured output:

```
Testing lg-recap.smoke-test

Ran 26 tests containing 78 assertions.
0 failures, 0 errors.
```

First run may take a few seconds while babashka resolves git dependencies from
`bb.edn`. Subsequent runs are faster.

## Optional: run via bb task alias

From `lg-clj/` either form is equivalent:

```bash
bb test
# or
bb run_tests.clj
```

Both exit non-zero if any assertion fails.

## Troubleshooting

**`Could not resolve symbol` or missing namespace on first `bb test`**

Ensure you are in `lg-clj/` (not the repo root). The babashka project paths are
declared in `lg-clj/bb.edn`, not at the repository root.

**Git dependency fetch fails**

Confirm SSH or HTTPS access to `github.com/com-junkawasaki/langgraph-clj` and
`langchain-clj`. Corporate proxies must allow git clone to GitHub.
