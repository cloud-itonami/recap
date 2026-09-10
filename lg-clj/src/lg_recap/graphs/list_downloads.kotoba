(ns lg-recap.graphs.list-downloads
  "recap `listDownloads` graph — paginated download history.

  NSID: com.etzhayyim.apps.recap.listDownloads
  Faithful clj port of `lg/lg_recap/graphs/list_downloads.py` (ADR-2606280030).

  Topology: START → query → END.

  The store read is an INJECTABLE edge (`*query-rows*`): contract is
  (filters) → seq of row maps, where filters = {:platform :status :limit :offset}.
  The default returns [] with {:error \"store not configured\"} (parity with the
  Python RW_URL-unset guard). DEVIATION: Python reads RisingWave via psycopg;
  this port keeps it injectable so the backend can be the kotoba Datom log."
  (:require [langgraph.graph :as g]))

(defn- clamp [v lo hi] (max lo (min hi v)))

(defn- as-int [v default]
  (cond
    (integer? v) v
    (string? v)  (try (Integer/parseInt v) (catch Exception _ default))
    :else        default))

;; ── injectable store read ───────────────────────────────────────────────────

(def ^:dynamic *query-rows*
  "Default: no store configured (parity with Python's RW_URL-unset path).
  Returns {:rows [] :error \"store not configured\"}."
  (fn [_filters] {:rows [] :error "store not configured"}))

(defn node-query [state]
  (let [limit  (clamp (as-int (:limit state) 50) 1 200)
        offset (max 0 (as-int (:offset state) 0))
        res    (*query-rows* {:platform (:platform state)
                              :status   (:status state)
                              :limit    limit :offset offset})]
    (if (:error res)
      {:items [] :error (:error res)}
      {:items (mapv (fn [r]
                      {:uri            (:vertex_id r)
                       :url            (:source_url r)
                       :platform       (:platform r)
                       :title          (:title r)
                       :durationSec    (:duration_sec r)
                       :blobKey        (:blob_key r)
                       :blobSizeBytes  (:blob_size_bytes r)
                       :status         (:status r)
                       :scope          (:scope r)
                       :createdAt      (:created_at r)})
                    (:rows res))
       :limit limit :offset offset})))

(defn build
  "Compile the listDownloads StateGraph (query)."
  []
  (-> (g/state-graph)
      (g/add-node :query node-query)
      (g/set-entry-point :query)
      (g/set-finish-point :query)
      (g/compile-graph)))

(def GRAPH (build))
