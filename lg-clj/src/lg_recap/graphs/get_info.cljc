(ns lg-recap.graphs.get-info
  "recap `getInfo` graph — fetch media metadata without downloading.

  NSID: com.etzhayyim.apps.recap.getInfo
  Faithful clj port of `lg/lg_recap/graphs/get_info.py` (ADR-2606280030).

  Topology: START → validate → get_metadata → END.

  The yt-dlp metadata fetch is an INJECTABLE edge (`*dump-json*`): contract is
  (url) → an info map with keyword keys (cheshire-parsed yt-dlp --dump-json) on
  success, or {:error \"...\"} on failure. The default impl shells out to the
  `yt-dlp` system binary via babashka.process (shelling out to an installed tool
  is allowed by the repo clj/bb rule). Tests rebind it to a stub so the graph
  topology + validation logic verify offline under bb."
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [langgraph.graph :as g]))

(def allowed-platforms
  #{"youtube" "tiktok" "instagram" "twitter" "x" "niconico"
    "bilibili" "vimeo" "twitch" "facebook" "reddit"})

(defn detect-platform
  "URL → platform keyword string, or \"unknown\". Shared by download/summarize
  (mirrors Python's get_info._detect_platform)."
  [url]
  (let [[_ scheme authority] (or (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]+)" (str url))
                                 [nil nil nil])
        host (some-> authority str/lower-case (str/split #":" 2) first)
        web? (contains? #{"http" "https"} (some-> scheme str/lower-case))
        domain? (fn [& domains]
                  (and web? host
                       (some #(or (= host %) (str/ends-with? host (str "." %))) domains)))]
    (cond
      (domain? "youtube.com" "youtu.be") "youtube"
      (domain? "tiktok.com") "tiktok"
      (domain? "instagram.com") "instagram"
      (domain? "twitter.com" "x.com") "x"
      (domain? "nicovideo.jp" "nico.ms") "niconico"
      (domain? "bilibili.com" "b23.tv") "bilibili"
      (domain? "vimeo.com") "vimeo"
      (domain? "twitch.tv") "twitch"
      (domain? "facebook.com" "fb.watch") "facebook"
      (domain? "reddit.com" "redd.it") "reddit"
      :else "unknown")))

(defn- clip
  "First n chars of s (yt-dlp error/url truncation parity)."
  [s n]
  (let [s (str s)] (subs s 0 (min n (count s)))))

;; ── injectable yt-dlp metadata edge ────────────────────────────────────────

(defn dump-json-with
  "Default `*dump-json*`: run `yt-dlp --dump-json` for url, parse stdout JSON.
  Returns the info map (keyword keys) or {:error \"yt-dlp: ...\"}."
  [process-sh cookies-file url]
  (when-not (fn? process-sh)
    (throw (ex-info "Recap metadata requires an explicit process capability"
                    {:capability :recap/yt-dlp-metadata})))
  (try
    (let [args  (concat ["yt-dlp" "--dump-json" "--no-playlist"]
                        (when (seq cookies-file) ["--cookies" cookies-file])
                        ["--remote-components" "ejs:github" url])
          {:keys [exit out err]} (apply process-sh args)]
      (if (zero? exit)
        (json/parse-string out true)
        {:error (str "yt-dlp: " (clip err 200))}))
    (catch Exception e {:error (clip (.getMessage e) 200)})))

(def ^:dynamic *dump-json* nil)

(defn dump-json [url]
  (when-not (fn? *dump-json*)
    (throw (ex-info "Recap metadata requires an explicit dump-json capability"
                    {:capability :recap/dump-json})))
  (*dump-json* url))

;; ── nodes ──────────────────────────────────────────────────────────────────

(defn node-validate [state]
  (let [url (str/trim (or (:url state) ""))]
    (cond
      (str/blank? url) {:error "url is required"}
      (= "unknown" (detect-platform url))
      {:error (str "unsupported platform for url: " (clip url 100))}
      :else {:platform (detect-platform url)})))

(defn node-get-metadata [state]
  (if (:error state)
    {}
    (let [res (dump-json (:url state))]
      (if (:error res)
        res
        (let [info    res
              formats (mapv (fn [f] {:format_id (:format_id f)
                                     :ext       (:ext f)
                                     :note      (:format_note f)
                                     :height    (:height f)
                                     :filesize  (:filesize f)})
                            (take-last 10 (:formats info)))]
          {:title       (:title info)
           :uploader    (or (:uploader info) (:channel info))
           :duration    (:duration info)
           :thumbnail   (:thumbnail info)
           :description (clip (or (:description info) "") 500)
           :upload_date (:upload_date info)
           :license     (:license info)
           :formats     formats})))))

(defn build
  "Compile the getInfo StateGraph (validate → get_metadata)."
  []
  (-> (g/state-graph)
      (g/add-node :validate node-validate)
      (g/add-node :get_metadata node-get-metadata)
      (g/add-edge :validate :get_metadata)
      (g/set-entry-point :validate)
      (g/set-finish-point :get_metadata)
      (g/compile-graph)))

(def GRAPH (build))
