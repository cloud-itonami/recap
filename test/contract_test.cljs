#!/usr/bin/env nbb
;; contract_test.cljs — recap's five declarative planes, checked against each
;; other.
;;
;; ## Why this lives at the repo root and not inside a plane
;;
;; recap declares the same handful of facts — which XRPC methods exist, which
;; upstream serves them, what this app is called — in five places that no
;; build step derives from one another:
;;
;;   src/app.ts                              kotodama-host-SDK edge, 4 commands
;;   lg-clj/src/lg_recap/server.cljc         NSID-MAP + GRAPHS registry
;;   kotodama.jsonld                         actor manifest (nanoid, triggers)
;;   wrangler.jsonc                          deployment (name, vars, routes)
;;   src/xrpc-proxy.ts                       preserved SvelteKit BFF, not wired
;;
;; `lg-clj/test/lg_recap/smoke_test.cljc` is a good suite, but it can only see
;; the Clojure plane: it asserts NSID-MAP against a copy of NSID-MAP written in
;; the same file. Nothing in this repo reads two planes together. The facts
;; below are *between* planes, so they belong to none of them — which is why
;; this file is at the root rather than under lg-clj/ or cljs/.
;;
;; ## 2026-09-07: the fifth plane stopped being served
;;
;; This app's front end moved from `svelte/` (SvelteKit) to `cljs/`
;; (shadow-cljs + reagent + re-frame). `svelte/src/routes/xrpc/[...path]/
;; +server.ts` was preserved — not deleted — at `src/xrpc-proxy.ts`, because
;; deleting it would have thrown away a production handler with no
;; replacement decided. It is no longer built by anything (there is no
;; SvelteKit adapter left in this repo, and `cljs/` does not build it
;; either), so the checks below that used to read "the entry the build
;; actually produces" now read a preserved-but-unwired file instead. The
;; textual facts inside it (which upstream it calls, whether it enforces an
;; NSID allowlist) have not changed, so those checks are pinned as before;
;; the checks that talked about what wrangler *deploys* were updated instead
;; — see `wrangler-has-no-main-so-assets-are-served-directly` below.
;;
;; These checks read source text and do not execute anything. A route table, an
;; NSID literal, a nanoid and an env var name are textual facts, and their
;; disagreements are visible in the text. Executing the planes would prove
;; less: the lg-clj suite runs against stubs, and the deployed worker needs
;; Cloudflare bindings no local run has.
;;
;; ## What "pinned" means here
;;
;; Some checks record agreement (edge NSIDs ↔ dispatch NSIDs) and some record
;; **disagreement** (the built entry is not src/app.ts; the two planes forward
;; to different upstreams). Both are pinned the same way: as the observed
;; value. A check going red therefore means "this fact moved", not necessarily
;; "someone broke it" — including the good case where a split gets repaired.
;; Each message says which reading applies.
;;
;; ## Exit codes are three-valued
;;
;;   0  every check passed
;;   1  a check failed — the report names which fact moved
;;   2  REFUSED — a file, anchor or extractor this depends on came back empty,
;;      so nothing was measured. A checker that cannot see its input must not
;;      answer "clean"; that is the failure mode CLAUDE.md's six questions are
;;      about.
;;
;; Run: nbb test/contract_test.cljs

(ns contract-test
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.set :as set]
            [clojure.string :as str]))

(def root
  "Repo root = the directory holding this test/ directory."
  (path/resolve (path/join (path/dirname (or js/__filename "test/contract_test.cljs")) "..")))

;; ── refusal ────────────────────────────────────────────────────────────────
;;
;; Collected rather than thrown, so one run reports every reason it could not
;; measure instead of only the first.

(def refusals (atom []))
(defn- refuse! [why] (swap! refusals conj why) nil)

(defn- read-source
  "Source text, or a refusal. Returns nil when absent — every caller treats nil
   as 'not measured', never as 'empty'."
  [rel]
  (let [p (path/join root rel)]
    (if-not (fs/existsSync p)
      (refuse! (str "missing source: " rel))
      (let [s (str (fs/readFileSync p "utf8"))]
        (if (str/blank? s)
          (refuse! (str "empty source: " rel))
          s)))))

(defn- read-json
  "Parsed JSON, or a refusal. wrangler.jsonc is read this way on purpose: if
   someone adds JSONC comments the parse fails and this refuses, rather than
   quietly measuring nothing. Refusing is the correct answer to 'I could not
   read my own input'."
  [rel]
  (when-let [s (read-source rel)]
    (try
      (js->clj (js/JSON.parse s))
      (catch :default e
        (refuse! (str "unparseable as JSON: " rel " — " (.-message e)))))))

(defn- between
  "The slice of `s` strictly between the first `open` and the following
   `close`. Refuses when either anchor is absent or out of order — an anchor
   that has been renamed must not silently widen the region to the whole file,
   which is how a scoped scan turns into a file-wide one."
  [s rel open close]
  (when s
    (let [i (str/index-of s open)
          j (when i (str/index-of s close i))]
      (cond
        (nil? i) (refuse! (str "anchor not found in " rel ": " (pr-str open)))
        (nil? j) (refuse! (str "closing anchor not found in " rel ": " (pr-str close)))
        :else (subs s (+ i (count open)) j)))))

(defn- matches
  "Every capture group 1 of `re` in `s`, de-duplicated, as a set. Refuses when
   fewer than `floor` distinct hits: an extractor that stopped matching returns
   the empty set, and the empty set satisfies every subset assertion below."
  [s label floor re]
  (when s
    (let [found (into #{} (map second) (re-seq re s))]
      (if (< (count found) floor)
        (refuse! (str "scanned " (count found) " " label
                      " (floor " floor ") — the extractor stopped matching"))
        found))))

(defn- one
  "Capture group 1 of the single expected hit, or a refusal."
  [s label re]
  (when s
    (or (second (re-find re s))
        (refuse! (str "not found: " label)))))

;; ── checks ──────────────────────────────────────────────────────────────────

(def results (atom []))

(defn- check [nm ok? msg]
  (swap! results conj {:name nm :ok? (boolean ok?) :msg msg}))

(defn- show [v] (pr-str (if (set? v) (vec (sort v)) v)))

(defn- check= [nm expected actual what]
  (check nm (= expected actual)
         (if (= expected actual)
           (str what " = " (show actual))
           (str what " moved"
                "\n         expected: " (show expected)
                "\n         actual:   " (show actual)
                (when (and (set? expected) (set? actual))
                  (str "\n         gone:     " (show (set/difference expected actual))
                       "\n         new:      " (show (set/difference actual expected))))))))

;; ── plane 1: src/app.ts — the kotodama-host-SDK edge ────────────────────────

(def app-ts (read-source "src/app.ts"))

(def edge-nsids
  (matches app-ts "nsid() literals in src/app.ts" 4
           #"nsid\(\"(com\.etzhayyim\.apps\.recap\.[A-Za-z]+)\"\)"))

(def edge-upstream
  (one app-ts "src/app.ts DISPATCHER_URL default"
       #"DISPATCHER_URL[^?]*\?\?\s*\"(https://[^\"]+)\""))

;; ── plane 2: lg-clj — the graph registry and dispatch table ─────────────────

(def server-cljc (read-source "lg-clj/src/lg_recap/server.cljc"))

(def graph-names
  (matches (between server-cljc "lg-clj/src/lg_recap/server.cljc"
                    "(def GRAPHS" "(def NSID-MAP")
           "GRAPHS keys" 5
           #"\"([a-z_]+)\"\s+[a-z-]+/GRAPH"))

(def nsid-map
  (let [region (between server-cljc "lg-clj/src/lg_recap/server.cljc"
                        "(def NSID-MAP" "(def ^:dynamic")]
    (when region
      (let [pairs (into {} (map (fn [[_ n g]] [n g]))
                        (re-seq #"\"(com\.etzhayyim\.apps\.recap\.[A-Za-z]+)\"\s+\"([a-z_]+)\"" region))]
        (if (< (count pairs) 4)
          (refuse! (str "scanned " (count pairs) " NSID-MAP entries (floor 4)"
                        " — the extractor stopped matching"))
          pairs)))))

;; ── plane 3: kotodama.jsonld — the actor manifest ───────────────────────────

(def manifest (read-json "kotodama.jsonld"))
(def manifest-nanoid (get manifest "nanoid"))
(def manifest-profile (get manifest "profile"))
(def manifest-collections
  (set (get-in manifest ["triggers" "subscribeRepos" "collections"])))

;; ── plane 4: wrangler.jsonc — what is actually deployed ─────────────────────

(def wrangler (read-json "wrangler.jsonc"))
(def wrangler-vars (get wrangler "vars"))
(def wrangler-main (get wrangler "main"))
(def wrangler-hosts
  (set (keep #(first (str/split (or (get % "pattern") "") #"/")) (get wrangler "routes"))))

;; ── plane 5: the preserved SvelteKit BFF — moved, not wired ─────────────────
;;
;; Was svelte/src/routes/xrpc/[...path]/+server.ts, "the entry the build
;; actually produces", before the 2026-09-07 cljs migration deleted svelte/.
;; Preserved byte-for-byte (past a prepended header comment) at this path so
;; the handler was not lost; nothing in this repo builds or serves it now
;; (see the header comment above).

(def bff-rel "src/xrpc-proxy.ts")
(def bff (read-source bff-rel))
(def bff-default-router
  (one bff (str bff-rel " DEFAULT_MCP_ROUTER_URL")
       #"DEFAULT_MCP_ROUTER_URL\s*=\s*'([^']+)'"))

;; ── the checks ──────────────────────────────────────────────────────────────

(defn run! []

  ;; --- agreement: the edge and the dispatch table name the same methods
  (when (and edge-nsids nsid-map)
    (check= "edge-and-dispatch-agree-on-the-nsid-set"
            (set (keys nsid-map)) edge-nsids
            "NSIDs registered in src/app.ts vs keys of lg-recap.server/NSID-MAP"))

  ;; --- agreement: every dispatched NSID resolves to a registered graph
  (when (and nsid-map graph-names)
    (let [dangling (set/difference (set (vals nsid-map)) graph-names)]
      (check "dispatch-nsids-resolve-to-registered-graphs"
             (empty? dangling)
             (if (empty? dangling)
               (str (count nsid-map) " NSIDs → " (show graph-names))
               (str "NSID-MAP points at graphs GRAPHS does not register: " (show dangling)
                    " — dispatch_xrpc would hand nil to run-graph")))))

  ;; --- agreement: the manifest only subscribes to dispatchable collections
  (when (and nsid-map (seq manifest-collections))
    (check "manifest-subscribe-collections-are-dispatchable"
           (set/subset? manifest-collections (set (keys nsid-map)))
           (str "kotodama.jsonld subscribeRepos.collections = " (show manifest-collections)
                (if (set/subset? manifest-collections (set (keys nsid-map)))
                  " (all dispatchable)"
                  (str " — not in NSID-MAP: "
                       (show (set/difference manifest-collections (set (keys nsid-map))))))))
    (check= "manifest-subscribes-to-download-only"
            #{"com.etzhayyim.apps.recap.download"} manifest-collections
            "the manifest subscribes to one of the four collections"))

  ;; --- agreement: one nanoid, repeated in four places by hand
  (when (and manifest-nanoid wrangler wrangler-vars)
    (check= "wrangler-app-nanoid-matches-the-manifest"
            manifest-nanoid (get wrangler-vars "APP_NANOID")
            "wrangler vars.APP_NANOID vs kotodama.jsonld nanoid")
    (check= "worker-name-is-kotodama-plus-the-nanoid"
            (str "kotodama-" manifest-nanoid) (get wrangler "name")
            "wrangler name")
    (check "a-route-is-served-at-the-nanoid-host"
           (contains? wrangler-hosts (str manifest-nanoid ".etzhayyim.com"))
           (str "wrangler route hosts = " (show wrangler-hosts))))

  ;; --- agreement: the deployment vars are a hand copy of the manifest profile
  (when (and manifest-profile wrangler-vars)
    (check= "wrangler-display-name-mirrors-the-manifest"
            (get manifest-profile "displayName") (get wrangler-vars "APP_DISPLAY_NAME")
            "APP_DISPLAY_NAME")
    (check= "wrangler-description-mirrors-the-manifest"
            (get manifest-profile "description") (get wrangler-vars "APP_DESCRIPTION")
            "APP_DESCRIPTION")
    (check= "wrangler-capabilities-mirror-the-manifest"
            (get manifest-profile "capabilities")
            (try (js->clj (js/JSON.parse (or (get wrangler-vars "APP_CAPABILITIES") "null")))
                 (catch :default _ :unparseable))
            "APP_CAPABILITIES (a JSON string inside JSON)"))

  ;; --- DISAGREEMENT (true both before and after 2026-09-07): the four
  ;; commands in src/app.ts are not deployed. Before the cljs migration
  ;; `wrangler main` was the SvelteKit adapter output; after it there is no
  ;; `main` at all (deployment is asset-only — see the next check). Either
  ;; way, no build config in this repo mentions src/app.ts. It is a plane
  ;; left behind by the extraction from etzhayyim/root — reading it as "the
  ;; edge" is reading dead source.
  (let [configs ["wrangler.jsonc" "cljs/shadow-cljs.edn" "cljs/package.json"]
        seen (keep (fn [rel]
                     (let [p (path/join root rel)]
                       (when (and (fs/existsSync p)
                                  (str/includes? (str (fs/readFileSync p "utf8")) "src/app"))
                         rel)))
                   configs)]
    (check "src-app-ts-is-built-by-nothing"
           (empty? seen)
           (if (empty? seen)
             (str "none of " (count configs) " build configs mentions src/app — so the "
                  (count (or edge-nsids [])) " commands registered there are declared, not served")
             (str "src/app.ts is now referenced by " (show (vec seen))
                  " — the orphan plane has been wired in; re-read who serves the NSIDs"))))

  ;; --- agreement: wrangler has no `main` script, so `assets.directory` is
  ;; served directly with nothing in front of it. Before 2026-09-07 this was
  ;; pinned the other way (`main` == the SvelteKit adapter output and
  ;; `assets.directory` == that same adapter's client dir — a check that a
  ;; `main` script and its assets agreed on which build produced them). The
  ;; cljs migration removed `main` outright rather than repointing it at
  ;; src/app.ts, specifically because src/app.ts never calls
  ;; `env.ASSETS.fetch()` — a worker script in front of assets that does not
  ;; forward to them would swallow every static request. If `main`
  ;; reappears, re-read whether it forwards to ASSETS before trusting static
  ;; content is still served.
  (when wrangler
    (check "wrangler-has-no-main-so-assets-are-served-directly"
           (and (nil? wrangler-main)
                (= "./cljs/public" (get-in wrangler ["assets" "directory"])))
           (str "main = " (pr-str wrangler-main)
                " / assets.directory = " (pr-str (get-in wrangler ["assets" "directory"])))))

  ;; --- DISAGREEMENT: the two planes forward to different upstreams.
  ;; src/app.ts proxies to the lg dispatcher; the preserved (unwired) BFF
  ;; calls the MCP router. They are not two routes to one service — they are
  ;; two services, and neither upstream changed when the BFF moved out of
  ;; svelte/.
  (when (and edge-upstream bff-default-router)
    (let [host #(second (re-find #"https://([^/]+)" %))]
      (check "the-declared-edge-and-the-preserved-edge-target-different-hosts"
             (not= (host edge-upstream) (host bff-default-router))
             (str "src/app.ts → " (pr-str (host edge-upstream))
                  " ; " bff-rel " → " (pr-str (host bff-default-router))
                  (if (= (host edge-upstream) (host bff-default-router))
                    " — they now agree; the split this pins has been repaired"
                    " — the lg-clj graphs are reachable only through the first")))))

  ;; --- agreement: the BFF default and the deployment var are the same literal
  (when (and bff-default-router wrangler-vars)
    (check= "bff-default-router-matches-the-wrangler-var"
            (get wrangler-vars "AGENTGATEWAY_MCP_ROUTER_URL") bff-default-router
            "MCP router URL, written out in both files"))

  ;; --- fact about the preserved file, not a claim about live traffic: the
  ;; unwired BFF has no NSID allowlist. Before 2026-09-07 this was live and
  ;; the message said the edge it described was live; after the cljs
  ;; migration nothing routes traffic through src/xrpc-proxy.ts at all, so
  ;; the same textual fact (no allowlist) is pinned without claiming it is
  ;; reachable. `[...path]` is forwarded verbatim as an MCP `tools/call` name
  ;; in the preserved source — if this file is ever revived, that absence is
  ;; what needs deciding first.
  (when bff
    (let [n (count (re-seq #"com\.etzhayyim\.apps\.recap\." bff))]
      (check "preserved-xrpc-proxy-has-no-recap-nsid-allowlist"
             (zero? n)
             (if (zero? n)
               (str bff-rel " forwards event.params.path unchanged as the MCP tool name"
                    " — no allowlist in the preserved (unwired) source")
               (str bff-rel " now mentions " n " recap NSID literal(s)"
                    " — an allowlist may have appeared; re-read it"))))))

;; ── report ──────────────────────────────────────────────────────────────────

(run!)

(let [rs @results
      failed (remove :ok? rs)
      refused @refusals]
  (println "── recap cross-plane contract checks ──")
  (println (str "SCANNED\tedge-nsids=" (count (or edge-nsids []))
                " nsid-map=" (count (or nsid-map {}))
                " graphs=" (count (or graph-names []))
                " manifest-collections=" (count manifest-collections)
                " wrangler-hosts=" (count wrangler-hosts)))
  (doseq [{:keys [name ok? msg]} rs]
    (println (str (if ok? "  ok   " "  FAIL ") name)
             (str "\n         " msg)))
  (cond
    (seq refused)
    (do (println)
        (println "REFUSED — nothing was measured for:")
        (doseq [r refused] (println (str "  · " r)))
        (println (str "Refusing to report a pass on " (count rs) " checks that did run;"
                      " a checker blind to its input must not answer \"clean\"."))
        (js/process.exit 2))

    (seq failed)
    (do (println)
        (println (str "recap contract: " (count failed) " of " (count rs) " checks FAILED"))
        (js/process.exit 1))

    :else
    (do (println)
        (println (str "recap contract: OK (" (count rs) " checks)"))
        (js/process.exit 0))))
