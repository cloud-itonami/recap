(ns lg-recap.server
  "lg-recap dispatch surface — clj port of `lg/lg_recap/server.py` (ADR-2606280030).

  The Python file is a FastAPI app exposing:
    POST /runs          → invoke graph synchronously
    POST /xrpc/{nsid}   → XRPC shim (NSID → graph mapping)
    GET  /ok /health    → liveness / readiness

  This namespace ports the ROUTING + graph registry (GRAPHS / NSID-MAP) and the
  invoke/serialize logic as plain clj functions (`dispatch-run`, `dispatch-xrpc`,
  `health`). Wrapping these in a concrete HTTP server (ring/http-kit) is left to
  the deployment layer — the graphs + dispatch are the load-bearing port. The
  Python FastAPI server (`lg/`) remains the deployed runtime and COEXISTS."
  (:require [langgraph.graph :as g]
            [lg-recap.graphs.health :as health]
            [lg-recap.graphs.get-info :as get-info]
            [lg-recap.graphs.download :as download]
            [lg-recap.graphs.list-downloads :as list-downloads]
            [lg-recap.graphs.summarize :as summarize]))

(def GRAPHS
  {"health"         health/GRAPH
   "download"       download/GRAPH
   "get_info"       get-info/GRAPH
   "list_downloads" list-downloads/GRAPH
   "summarize"      summarize/GRAPH})

(def NSID-MAP
  {"com.etzhayyim.apps.recap.download"      "download"
   "com.etzhayyim.apps.recap.getInfo"       "get_info"
   "com.etzhayyim.apps.recap.listDownloads" "list_downloads"
   "com.etzhayyim.apps.recap.summarize"     "summarize"})

(def ^:dynamic *api-key* "")

(defn check-api-key
  "Mirrors server._check_api_key: if LG_API_KEY is set, x-api-key must match."
  [x-api-key]
  (if (and (seq *api-key*) (not= x-api-key *api-key*))
    {:status 401 :body {:error "invalid api key"}}
    nil))

(defn- run-graph [graph input]
  (try
    {:status 200 :body (g/invoke graph (or input {}))}
    (catch Exception e
      {:status 500 :body {:error (let [m (str (.getMessage e))]
                                   (subs m 0 (min 300 (count m))))}})))

(defn health
  "GET /ok | /health → {:ok true :graphs [...]}"
  []
  {:status 200 :body {:ok true :graphs (vec (keys GRAPHS))}})

(defn dispatch-run
  "POST /runs body → {:status :body}. body keys: :assistant_id :input/:inputs.
  Enforces the optional x-api-key (pass via opts {:x-api-key ...})."
  ([body] (dispatch-run body {}))
  ([body {:keys [x-api-key]}]
   (or (check-api-key x-api-key)
       (let [aid   (or (:assistant_id body) "")
             graph (get GRAPHS aid)]
         (if (nil? graph)
           {:status 404 :body {:error (str "unknown graph: " aid)}}
           (run-graph graph (or (:input body) (:inputs body) {})))))))

(defn dispatch-xrpc
  "POST /xrpc/{nsid} body → {:status :body}. NSID mapped to a graph; body is the
  graph input. /xrpc is unauthenticated (parity with the Python server)."
  [nsid body]
  (let [gname (get NSID-MAP nsid)]
    (if (nil? gname)
      {:status 404 :body {:error (str "unknown nsid: " nsid)}}
      (run-graph (get GRAPHS gname) (or body {})))))
