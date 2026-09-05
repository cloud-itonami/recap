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
;;   svelte/src/routes/xrpc/[...path]/       the entry that is actually built
;;
;; `lg-clj/test/lg_recap/smoke_test.cljc` is a good suite, but it can only see
;; the Clojure plane: it asserts NSID-MAP against a copy of NSID-MAP written in
;; the same file. Nothing in this repo reads two planes together. The facts
;; below are *between* planes, so they belong to none of them — which is why
;; this file is at the root rather than under lg-clj/ or svelte/.
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

;; ── plane 5: the SvelteKit BFF — the entry the build actually produces ──────

(def bff-rel "svelte/src/routes/xrpc/[...path]/+server.ts")
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

  ;; --- DISAGREEMENT: the four commands in src/app.ts are not deployed.
  ;; wrangler `main` is the SvelteKit adapter output, and no build config in
  ;; this repo mentions src/app.ts. It is a plane left behind by the extraction
  ;; from etzhayyim/root — reading it as "the edge" is reading dead source.
  (when wrangler-main
    (let [configs ["wrangler.jsonc" "svelte/vite.config.ts" "svelte/svelte.config.js"
                   "svelte/package.json" "svelte/tsconfig.json"]
          seen (keep (fn [rel]
                       (let [p (path/join root rel)]
                         (when (and (fs/existsSync p)
                                    (str/includes? (str (fs/readFileSync p "utf8")) "src/app"))
                           rel)))
                     configs)]
      (check "src-app-ts-is-built-by-nothing"
             (and (empty? seen) (not (str/includes? wrangler-main "src/app")))
             (if (and (empty? seen) (not (str/includes? wrangler-main "src/app")))
               (str "wrangler main = " (pr-str wrangler-main) " and none of "
                    (count configs) " build configs mentions src/app — so the "
                    (count (or edge-nsids [])) " commands registered there are declared, not served")
               (str "src/app.ts is now referenced by " (show (vec seen))
                    " / main=" (pr-str wrangler-main)
                    " — the orphan plane has been wired in; re-read who serves the NSIDs")))
      (check "wrangler-main-and-assets-share-the-adapter-output"
             (and (str/starts-with? wrangler-main "svelte/.svelte-kit/cloudflare/")
                  (str/includes? (or (get-in wrangler ["assets" "directory"]) "")
                                 "svelte/.svelte-kit/cloudflare/"))
             (str "main = " (pr-str wrangler-main)
                  " / assets.directory = " (pr-str (get-in wrangler ["assets" "directory"]))))))

  ;; --- DISAGREEMENT: the two planes forward to different upstreams.
  ;; src/app.ts proxies to the lg dispatcher; the built BFF calls the MCP
  ;; router. They are not two routes to one service — they are two services.
  (when (and edge-upstream bff-default-router)
    (let [host #(second (re-find #"https://([^/]+)" %))]
      (check "the-declared-edge-and-the-built-edge-target-different-hosts"
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

  ;; --- DISAGREEMENT: the deployed path has no NSID allowlist.
  ;; `[...path]` is forwarded verbatim as an MCP `tools/call` name, so the four
  ;; NSIDs this repo declares constrain nothing at the edge that is live.
  (when bff
    (let [n (count (re-seq #"com\.etzhayyim\.apps\.recap\." bff))]
      (check "deployed-xrpc-route-names-no-recap-nsid"
             (zero? n)
             (if (zero? n)
               (str bff-rel " forwards event.params.path unchanged as the MCP tool name"
                    " — no allowlist, so recap's 4 NSIDs are not enforced where traffic lands")
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
