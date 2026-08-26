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
| `src/app.ts` | Worker / edge entry wiring |
| `wrangler.jsonc` | Cloudflare deployment config |

## Quick verify (no network)

```bash
cd lg-clj && bb test
```

See `docs/operator-quickstart.md` for measured output and prerequisites.
