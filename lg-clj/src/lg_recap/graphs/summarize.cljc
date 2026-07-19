(ns lg-recap.graphs.summarize
  "recap `summarize` graph — extract transcript and generate an LLM summary.

  NSID: com.etzhayyim.apps.recap.summarize
  Faithful clj port of `lg/lg_recap/graphs/summarize.py` (ADR-2606280030).

  Topology: START → validate → extract_transcript → summarize_llm → write_record → END.

  DEVIATION (noted): the Python posts to a RunPod vLLM URL. Per ADR-2605215000 /
  ADR-2606172359 (Murakumo DEFAULT-PREFERRED) the LLM edge here defaults to the
  Murakumo loopback gateway (LiteLLM 127.0.0.1:4000) and asserts the endpoint is
  on the Murakumo fleet allowlist (ibuki pattern). The transcript fetch + DB
  write are injectable edges, exactly as in the download graph.

  Injectable edges (tests rebind to stubs):
    *fetch-transcript* (url lang) → {:meta <map> :transcript <str> :transcript-lang <str>}
                                    | {:meta <map> :error \"...\"} | {:error \"...\"}
    *llm-chat*         (system user) → summary string | {:error \"...\"}
    *write-record*     (record-map)  → {:summary_uri <vertex-id>} | {:error ..} | {}"
  (:require #?(:clj [cheshire.core :as json])
            [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-recap.graphs.get-info :as gi]))

(def transcript-chunk-chars 6000)
(def summary-max-tokens 800)

(def default-config {:repo "did:web:recap.etzhayyim.com"
                     :owner "did:web:recap.etzhayyim.com"
                     :llm-url "http://127.0.0.1:4000/v1"
                     :llm-model "gemma3:4b"
                     :llm-timeout-sec 120.0})
(def ^:dynamic *config* default-config)

;; Murakumo fleet (ADR-2605215000) — the ONLY inference endpoints representable.
(def murakumo-allowed-hosts
  #{"127.0.0.1:4000" "localhost:4000"
    "192.168.1.70:8077" "192.168.1.70:11434"
    "127.0.0.1:11434" "localhost:11434"})

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn vtt->text
  "Strip a WEBVTT subtitle blob to deduped plain text (mirrors _vtt_to_text)."
  [vtt]
  (let [texts (->> (str/split-lines (str vtt))
                   (map str/trim)
                   (remove (fn [line]
                             (or (str/blank? line)
                                 (str/starts-with? line "WEBVTT")
                                 (re-find #"^\d{2}:\d{2}" line)
                                 (str/includes? line "-->"))))
                   (map (fn [line]
                          (-> line
                              (str/replace #"<[^>]+>" "")
                              (str/replace #"&amp;" "&")
                              (str/replace #"&lt;" "<")
                              (str/replace #"&gt;" ">"))))
                   (remove str/blank?))
        deduped (reduce (fn [acc t]
                          (if (= (peek acc) t) acc (conj acc t)))
                        [] texts)]
    (str/join " " deduped)))

;; ── injectable LLM edge (Murakumo loopback default) ─────────────────────────

(defn assert-murakumo
  "Refuse any LLM endpoint outside the Murakumo fleet (http only)."
  [endpoint]
  (let [[_ scheme host] (or (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)" (str endpoint))
                            [nil nil nil])]
    (when-not (and (= "http" (some-> scheme str/lower-case))
                   (contains? murakumo-allowed-hosts (some-> host str/lower-case)))
      (throw (ex-info (str "inference endpoint " (pr-str endpoint)
                           " is outside the Murakumo fleet (ADR-2605215000)")
                      {:murakumo-only-violation true :endpoint endpoint})))))

(defn llm-chat-with
  "Default `*llm-chat*`: POST a chat-completions request to the Murakumo
  loopback gateway. Returns the summary text or {:error ...}."
  [http-post {:keys [llm-url llm-model llm-timeout-sec]} system user]
  (when-not (fn? http-post)
    (throw (ex-info "Recap inference requires an explicit HTTP POST capability"
                    {:capability :recap/murakumo-http-post})))
  (try
    (let [llm-url (str/replace (str llm-url) #"/+$" "")
          _ (assert-murakumo llm-url)
          resp     (http-post (str llm-url "/chat/completions")
                         {:headers {"Content-Type" "application/json"}
                          :timeout (long (* 1000 llm-timeout-sec))
                          :body (json/generate-string {:model llm-model
                                           :messages [{:role "system" :content system}
                                                      {:role "user" :content user}]
                                           :max_tokens summary-max-tokens
                                           :temperature 0.3})})
          status   (:status resp)]
      (if (>= status 400)
        {:error (str "vllm " status ": " (clip (:body resp) 200))}
        (let [body (json/parse-string (:body resp) true)
              txt  (some-> (get-in body [:choices 0 :message :content]) str str/trim)]
          (if (seq txt) txt {:error "LLM returned empty summary"}))))
    (catch Exception e {:error (clip (.getMessage e) 200)})))

(def ^:dynamic *llm-chat* nil)
(def ^:dynamic *fetch-transcript*
  "Default: no transcript source wired offline."
  (fn [_url _lang] {:error "transcript source not configured"}))
(def ^:dynamic *write-record* (fn [_record] {}))

(defn llm-chat [system user]
  (when-not (fn? *llm-chat*)
    (throw (ex-info "Recap inference requires an explicit chat capability"
                    {:capability :recap/chat})))
  (*llm-chat* system user))

;; ── nodes ──────────────────────────────────────────────────────────────────

(defn node-validate [state]
  (let [url (str/trim (or (:url state) ""))]
    (cond
      (str/blank? url) {:error "url is required"}
      (= "unknown" (gi/detect-platform url))
      {:error (str "unsupported platform for url: " (clip url 100))}
      :else {:platform (gi/detect-platform url)
             :lang (str/trim (or (:lang state) "ja"))})))

(defn node-extract-transcript [state]
  (if (:error state)
    {}
    (let [res  (*fetch-transcript* (:url state) (or (:lang state) "ja"))
          meta (:meta res)
          base (when meta
                 {:title        (:title meta)
                  :uploader     (or (:uploader meta) (:channel meta))
                  :duration_sec (:duration meta)
                  :license      (:license meta)
                  :upload_date  (:upload_date meta)})]
      (cond
        (and (:error res) (not meta)) {:error (:error res)}
        (:error res) (assoc base :error (:error res))
        :else (let [text (let [t (str (:transcript res))]
                           (if (> (count t) transcript-chunk-chars)
                             (str (subs t 0 transcript-chunk-chars) " …[truncated]")
                             t))]
                (assoc base :transcript text
                       :transcript_lang (:transcript-lang res)))))))

(defn node-summarize-llm [state]
  (if (:error state)
    {}
    (let [transcript (or (:transcript state) "")]
      (if (str/blank? transcript)
        {:error "no transcript to summarize"}
        (let [title    (or (:title state) "")
              uploader (or (:uploader state) "")
              lang     (or (:lang state) "ja")
              duration (:duration_sec state)
              dur-str  (if duration (str (quot duration 60) "分" (rem duration 60) "秒") "不明")
              lang-ins (if (= lang "ja") "日本語" (str "language: " lang))
              system   (str "You are a research assistant. Summarize the video transcript in "
                            lang-ins ". Structure: ① one-sentence overview, ② 3-5 key points as "
                            "bullets, ③ one-sentence conclusion. Be concise and factual. "
                            "If the transcript is incomplete or unclear, note that.")
              user     (str "Title: " title "\nCreator: " uploader "\nDuration: " dur-str
                            "\n\nTranscript:\n" transcript)
              res      (llm-chat system user)]
          (if (map? res) res {:summary res}))))))

(defn node-write-record [state]
  (if-not (:summary state)
    {}
    (*write-record* (assoc state :repo (:repo *config*) :owner (:owner *config*)))))

(defn build
  "Compile the summarize StateGraph (validate → extract → summarize → record)."
  []
  (-> (g/state-graph)
      (g/add-node :validate node-validate)
      (g/add-node :extract_transcript node-extract-transcript)
      (g/add-node :summarize_llm node-summarize-llm)
      (g/add-node :write_record node-write-record)
      (g/add-edge :validate :extract_transcript)
      (g/add-edge :extract_transcript :summarize_llm)
      (g/add-edge :summarize_llm :write_record)
      (g/set-entry-point :validate)
      (g/set-finish-point :write_record)
      (g/compile-graph)))

(def GRAPH (build))
