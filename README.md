# recap

`cloud-itonami/recap` is the **recap** app: a LangGraph-backed service for
downloading, listing, and summarizing media from configured sources. The
Clojure port under `lg-clj/` holds the graph registry, dispatch surface, and
offline smoke tests; the ClojureScript app under `cljs/` is the operator UI
shell (reagent + re-frame + jp-go-dds, migrated off Svelte — see
`git log -- svelte/` for the retired SvelteKit scaffold).

Canonical metadata lives in `README.edn` (`com-etzhayyim-app-recap`). Operator
steps that were actually executed are in `docs/operator-quickstart.md`.

## Layout

| path | role |
|---|---|
| `lg-clj/` | LangGraph graphs + `lg-recap.server` dispatch (babashka test suite) |
| `cljs/` | Front-end (shadow-cljs + reagent + re-frame + jp-go-dds); build output is `cljs/public` |
| `src/app.ts` | kotodama-host-SDK command surface — **declared, not built**: no build config references it (`test/contract_test.kotoba` pins this) |
| `src/xrpc-proxy.ts` | SvelteKit `+server.ts` preserved byte-for-byte from the retired `svelte/` dir — **not wired**, will not run as-is (see file header) |
| `wrangler.jsonc` | Cloudflare deployment config — no `main` script; `assets.directory` serves the static `cljs/public` build directly |
| `test/` | cross-plane contract checks (nbb, no deps) |

## Quick verify (no network)

Two suites, and they see different things.

```bash
nbb test/contract_test.kotoba   # the five planes, checked against each other
cd lg-clj && kbb -M:test          # the graph registry and dispatch, under stubs
```

`test/contract_test.kotoba` is at the root because the facts it checks are
*between* the planes listed above — which XRPC methods exist, which upstream
serves them, what this app is called. Each of those is written by hand in more
than one file and derived by nothing, so they drift silently. Some of its
checks pin agreement, some pin **disagreement** (the served surface is not
`src/app.ts`; `src/app.ts` and the preserved-but-unwired `src/xrpc-proxy.ts`
call different upstreams); the failure message says which. It exits `2` — not
`0` — when a file or anchor it reads has moved, so "could not measure" never
looks like "clean". It needs only nbb and the checkout.

`lg-clj/bb test` resolves git dependencies on first run; see
`docs/operator-quickstart.md` for measured output and prerequisites.

## Front-end build (cljs/)

```bash
cd cljs
npm install
amu compile --target wasm32-browser app     # -> cljs/public/js/
```

Output is a single-page static build (`cljs/public/index.html` + `js/`) —
there is no worker script (`wrangler.jsonc` has no `main`); Cloudflare serves
`cljs/public` directly as static assets.
