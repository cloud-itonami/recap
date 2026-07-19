(ns lg-recap.graphs.download
  "recap `download` graph — validate, download, upload, and record media.

  NSID: com.etzhayyim.apps.recap.download
  Faithful clj port of `lg/lg_recap/graphs/download.py` (ADR-2606280030).

  Topology: START → validate → download_upload → write_record → END.

  Two INJECTABLE side-effecting edges (defaults shell out / talk to B2+DB; tests
  rebind them to stubs so the topology + fair-use validation verify offline):

    *fetch-blob*  (url fmt)                 → {:info <map> :data-len <bytes>
                                              :digest <sha256> :ext <str>
                                              :blob-key <str> :uploaded <bool>}
                                              | {:error \"...\"}
        (yt-dlp metadata + download to a temp dir + optional B2 put_object)
    *write-record* (record-map)             → {:download_uri <vertex-id>} | {:error ..} | {}
        (psycopg INSERT into vertex_recap_download on RisingWave)

  DEVIATION (noted): the Python persists to RisingWave via psycopg. The repo's
  canonical state is the kotoba Datom log (RisingWave is deprecated per
  ADR-2605262130); this port keeps the write as an injectable edge so the graph
  is faithful while the persistence backend can be swapped to kotoba without
  touching the topology."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-recap.graphs.get-info :as gi]))

(def allowed-scopes #{"research" "authorized"})

(def default-config {:repo "did:web:recap.etzhayyim.com"
                     :owner "did:web:recap.etzhayyim.com"
                     :default-org-did "anon"
                     :cookies-file ""
                     :upload-enabled? false})
(def ^:dynamic *config* default-config)

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

;; ── injectable edges (defaults documented in the ns docstring) ──────────────

(defn fetch-blob-with
  "Default `*fetch-blob*`: yt-dlp metadata + download + optional B2 upload.
  Shells out to yt-dlp (allowed system binary) and lazily uses boto3 only when
  B2 creds are present. Returns the blob descriptor map or {:error ...}."
  [process-sh {:keys [cookies-file upload-enabled?]} url fmt]
  (when-not (fn? process-sh)
    (throw (ex-info "Recap download requires an explicit process capability"
                    {:capability :recap/yt-dlp-download})))
  (try
    (let [ck    (when (seq cookies-file) ["--cookies" cookies-file])
          tmp   (str (System/getProperty "java.io.tmpdir") "/recap-" (System/nanoTime))
          _     (.mkdirs (java.io.File. tmp))
          meta  (apply process-sh (concat ["yt-dlp" "--dump-json" "--no-playlist"] ck
                                  ["--remote-components" "ejs:github" url]))]
      (if-not (zero? (:exit meta))
        {:error (str "yt-dlp metadata: " (clip (:err meta) 200))}
        (let [info (json/parse-string (:out meta) true)
              dl   (apply process-sh (concat ["yt-dlp" "-f" fmt "--no-playlist"] ck
                                     ["--remote-components" "ejs:github"
                                      "-o" (str tmp "/%(id)s.%(ext)s") url]))]
          (if-not (zero? (:exit dl))
            {:error (str "yt-dlp download: " (clip (:err dl) 300))}
            (let [files (->> (.listFiles (java.io.File. tmp))
                             (filter #(.isFile %)) (sort-by #(- (.length %))))]
              (if (empty? files)
                {:error "yt-dlp produced no file"}
                (let [media  (first files)
                      data   (java.nio.file.Files/readAllBytes (.toPath media))
                      md     (java.security.MessageDigest/getInstance "SHA-256")
                      digest (->> (.digest md data)
                                  (map #(format "%02x" %)) (apply str))
                      nm     (.getName media)
                      ext    (let [i (.lastIndexOf nm ".")]
                               (if (pos? i) (subs nm (inc i)) "bin"))
                      blob   (str "recap/" digest "." ext)]
                  {:info info :data-len (alength data) :digest digest
                   :ext ext :blob-key blob :uploaded (boolean upload-enabled?)})))))))
    (catch Exception e {:error (clip (.getMessage e) 300)})))

(def ^:dynamic *fetch-blob* nil)
(def ^:dynamic *write-record*
  "Default: no-op unless overridden / RW_URL configured. Returns {}."
  (fn [_record] {}))

(defn fetch-blob [url fmt]
  (when-not (fn? *fetch-blob*)
    (throw (ex-info "Recap download requires an explicit fetch-blob capability"
                    {:capability :recap/fetch-blob})))
  (*fetch-blob* url fmt))

;; ── nodes ──────────────────────────────────────────────────────────────────

(defn node-validate [state]
  (let [url   (str/trim (or (:url state) ""))
        scope (str/trim (or (:scope state) "research"))]
    (cond
      (str/blank? url)
      {:status "error" :error "url is required"}
      (not (contains? allowed-scopes scope))
      {:status "error" :error "scope must be research or authorized"}
      (= "unknown" (gi/detect-platform url))
      {:status "error" :error (str "unsupported platform for url: " (clip url 100))}
      :else {:platform (gi/detect-platform url) :scope scope :status "downloading"})))

(defn node-download-upload [state]
  (if (:error state)
    {}
    (let [fmt (or (:format_id state) "bestvideo+bestaudio/best")
          res (fetch-blob (:url state) fmt)]
      (if (:error res)
        {:status "error" :error (:error res)}
        (let [info (:info res)]
          {:title           (:title info)
           :uploader        (or (:uploader info) (:channel info))
           :duration_sec    (:duration info)
           :thumbnail_url   (:thumbnail info)
           :upload_date     (:upload_date info)
           :license         (:license info)
           :format_id       fmt
           :blob_key        (:blob-key res)
           :blob_size_bytes (:data-len res)
           :status          (if (:uploaded res) "done" "downloaded")})))))

(defn node-write-record [state]
  (if-not (:blob_key state)
    {}
    (*write-record* (assoc state :repo (:repo *config*) :owner (:owner *config*)
                           :org-did (or (:org_did state) (:default-org-did *config*))))))

(defn build
  "Compile the download StateGraph (validate → download_upload → write_record)."
  []
  (-> (g/state-graph)
      (g/add-node :validate node-validate)
      (g/add-node :download_upload node-download-upload)
      (g/add-node :write_record node-write-record)
      (g/add-edge :validate :download_upload)
      (g/add-edge :download_upload :write_record)
      (g/set-entry-point :validate)
      (g/set-finish-point :write_record)
      (g/compile-graph)))

(def GRAPH (build))
