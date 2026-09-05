# recap

`cloud-itonami/recap` is the **recap** app: a LangGraph-backed service for
downloading, listing, and summarizing media from configured sources. The
Clojure port under `lg-clj/` holds the graph registry, dispatch surface, and
offline smoke tests; the Svelte app under `svelte/` is the operator UI shell.

Canonical metadata lives in `README.edn` (`com-etzhayyim-app-recap`). Operator
steps that were actually executed are in `docs/operator-quickstart.md`.

## Layout

| path | role |
|---|---|
| `lg-clj/` | LangGraph graphs + `lg-recap.server` dispatch (babashka test suite) |
| `svelte/` | Front-end (Vite + SvelteKit) |
| `src/app.ts` | kotodama-host-SDK command surface — **declared, not built**: no build config references it (`test/contract_test.cljs` pins this) |
| `wrangler.jsonc` | Cloudflare deployment config — `main` is the SvelteKit adapter output, so the served XRPC entry is `svelte/src/routes/xrpc/[...path]/+server.ts` |
| `test/` | cross-plane contract checks (nbb, no deps) |

## Quick verify (no network)

Two suites, and they see different things.

```bash
nbb test/contract_test.cljs   # the five planes, checked against each other
cd lg-clj && bb test          # the graph registry and dispatch, under stubs
```

`test/contract_test.cljs` is at the root because the facts it checks are
*between* the planes listed above — which XRPC methods exist, which upstream
serves them, what this app is called. Each of those is written by hand in more
than one file and derived by nothing, so they drift silently. Some of its
checks pin agreement, some pin **disagreement** (the built worker is not
`src/app.ts`; the two planes call different upstreams); the failure message
says which. It exits `2` — not `0` — when a file or anchor it reads has moved,
so "could not measure" never looks like "clean". It needs only nbb and the
checkout.

`lg-clj/bb test` resolves git dependencies on first run; see
`docs/operator-quickstart.md` for measured output and prerequisites.
