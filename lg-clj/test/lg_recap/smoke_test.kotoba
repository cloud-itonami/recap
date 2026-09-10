(ns lg-recap.smoke-test
  "Smoke tests for the lg-recap clj port — clojure.test analogue of the Python
  `tests/test_smoke.py`, plus node-behaviour tests the original could not run
  offline (yt-dlp/DB/LLM are injectable here, so validation + transforms verify
  under bb with stubs)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [lg-recap.server :as server]
            [lg-recap.graphs.health :as health]
            [lg-recap.graphs.get-info :as gi]
            [lg-recap.graphs.download :as dl]
            [lg-recap.graphs.list-downloads :as ld]
            [lg-recap.graphs.summarize :as sm]))

(def expected-graphs #{"health" "download" "get_info" "list_downloads" "summarize"})

(def expected-nsid-map
  {"com.etzhayyim.apps.recap.download"      "download"
   "com.etzhayyim.apps.recap.getInfo"       "get_info"
   "com.etzhayyim.apps.recap.listDownloads" "list_downloads"
   "com.etzhayyim.apps.recap.summarize"     "summarize"})

;; ── server registry parity (mirrors test_smoke.py) ──────────────────────────

(deftest graphs-match-expected-set
  (is (= expected-graphs (set (keys server/GRAPHS)))))

(deftest nsid-map-completeness
  (is (= expected-nsid-map server/NSID-MAP)))

(deftest nsid-map-references-known-graphs
  (doseq [[nsid gname] server/NSID-MAP]
    (is (contains? server/GRAPHS gname) (str nsid " → " gname " not in GRAPHS"))))

(deftest all-graphs-invocable
  (doseq [[nm graph] server/GRAPHS]
    (is (some? graph) (str "GRAPHS[" nm "] nil"))))

;; ── dispatch surface (/ok, /runs, /xrpc) ────────────────────────────────────

(deftest health-endpoint
  (let [r (server/health)]
    (is (= 200 (:status r)))
    (is (true? (get-in r [:body :ok])))
    (is (= expected-graphs (set (get-in r [:body :graphs]))))))

(deftest unknown-assistant-404
  (is (= 404 (:status (server/dispatch-run {:assistant_id "nope" :input {}})))))

(deftest unknown-nsid-404
  (is (= 404 (:status (server/dispatch-xrpc "com.etzhayyim.apps.recap.unknownMethod" {})))))

(deftest api-key-guard
  (testing "no key configured → pass"
    (is (nil? (server/check-api-key ""))))
  (testing "configured key mismatch → 401"
    (binding [server/*api-key* "secret"]
      (is (= 401 (:status (server/dispatch-run {:assistant_id "health"}
                                               {:x-api-key "wrong"})))))))

;; ── health graph end-to-end ─────────────────────────────────────────────────

(deftest health-graph-invokes
  (is (true? (:ok (g/invoke health/GRAPH {})))))

;; ── platform detection (get_info) ───────────────────────────────────────────

(deftest detect-platform-cases
  (is (= "youtube"   (gi/detect-platform "https://youtu.be/abc")))
  (is (= "youtube"   (gi/detect-platform "https://www.youtube.com/watch?v=x")))
  (is (= "tiktok"    (gi/detect-platform "https://www.tiktok.com/@a/video/1")))
  (is (= "x"         (gi/detect-platform "https://x.com/a/status/1")))
  (is (= "niconico"  (gi/detect-platform "https://www.nicovideo.jp/watch/sm1")))
  (is (= "bilibili"  (gi/detect-platform "https://b23.tv/abc")))
  (is (= "reddit"    (gi/detect-platform "https://redd.it/abc")))
  (is (= "unknown"   (gi/detect-platform "https://example.com/x")))
  (is (= "unknown"   (gi/detect-platform "https://youtube.com.attacker.example/x")))
  (is (= "unknown"   (gi/detect-platform "not-a-url/youtube.com"))))

;; ── get_info graph: validate guards + metadata via stub ─────────────────────

(deftest get-info-validate-guards
  (is (= "url is required" (:error (g/invoke gi/GRAPH {:url ""}))))
  (is (re-find #"unsupported platform" (:error (g/invoke gi/GRAPH {:url "https://example.com"})))))

(deftest get-info-metadata-stubbed
  (binding [gi/*dump-json* (fn [_url]
                             {:title "T" :channel "C" :duration 12
                              :description (apply str (repeat 600 "x"))
                              :formats (vec (for [i (range 15)]
                                              {:format_id (str i) :ext "mp4"
                                               :format_note "n" :height 720 :filesize 1}))})]
    (let [out (g/invoke gi/GRAPH {:url "https://youtu.be/abc"})]
      (is (= "youtube" (:platform out)))
      (is (= "T" (:title out)))
      (is (= "C" (:uploader out)))
      (is (= 500 (count (:description out))) "description clipped to 500")
      (is (= 10 (count (:formats out))) "only last 10 formats kept"))))

;; ── download graph: fair-use scope gate + happy path via stubs ──────────────

(deftest download-scope-gate
  (let [out (g/invoke dl/GRAPH {:url "https://youtu.be/abc" :scope "piracy"})]
    (is (= "error" (:status out)))
    (is (= "scope must be research or authorized" (:error out)))))

(deftest download-bad-url
  (is (= "url is required" (:error (g/invoke dl/GRAPH {:url ""})))))

(deftest download-happy-path-stubbed
  (binding [dl/*fetch-blob* (fn [_url _fmt]
                              {:info {:title "Vid" :uploader "U" :duration 30}
                               :data-len 1024 :digest "deadbeef" :ext "mp4"
                               :blob-key "recap/deadbeef.mp4" :uploaded true})
            dl/*write-record* (fn [_rec] {:download_uri "at://repo/dl/x"})]
    (let [out (g/invoke dl/GRAPH {:url "https://youtu.be/abc" :scope "research"})]
      (is (= "done" (:status out)))
      (is (= "recap/deadbeef.mp4" (:blob_key out)))
      (is (= 1024 (:blob_size_bytes out)))
      (is (= "at://repo/dl/x" (:download_uri out))))))

(deftest download-error-skips-downstream
  (binding [dl/*fetch-blob* (fn [_ _] {:error "yt-dlp download: boom"})]
    (let [out (g/invoke dl/GRAPH {:url "https://youtu.be/abc" :scope "research"})]
      (is (= "error" (:status out)))
      (is (re-find #"boom" (:error out)))
      (is (nil? (:blob_key out))))))

;; ── list_downloads graph: pagination clamp + row mapping ─────────────────────

(deftest list-downloads-default-empty
  (let [out (g/invoke ld/GRAPH {})]
    (is (= [] (:items out)))
    (is (= "store not configured" (:error out)))))

(deftest list-downloads-clamps-and-maps
  (binding [ld/*query-rows*
            (fn [filters]
              (is (= 200 (:limit filters)) "limit clamped to 200")
              (is (= 0 (:offset filters)))
              {:rows [{:vertex_id "at://x" :source_url "u" :platform "youtube"
                       :title "T" :duration_sec 9 :blob_key "k" :blob_size_bytes 7
                       :status "done" :scope "research" :created_at "2026"}]})]
    (let [out (g/invoke ld/GRAPH {:limit 9999 :offset -5})]
      (is (= 200 (:limit out)))
      (is (= 0 (:offset out)))
      (is (= 1 (count (:items out))))
      (is (= "at://x" (-> out :items first :uri)))
      (is (= 7 (-> out :items first :blobSizeBytes))))))

;; ── summarize graph: vtt→text + LLM via stub + Murakumo guard ───────────────

(deftest vtt-to-text-dedup
  (is (= "hello world"
         (sm/vtt->text "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<c>hello</c>\nhello\nworld"))))

(deftest summarize-bad-url
  (is (= "url is required" (:error (g/invoke sm/GRAPH {:url ""})))))

(deftest summarize-happy-path-stubbed
  (binding [sm/*fetch-transcript* (fn [_url _lang]
                                    {:meta {:title "Vid" :uploader "U" :duration 90}
                                     :transcript "some long transcript text"
                                     :transcript-lang "ja"})
            sm/*llm-chat* (fn [_sys _user] "① overview ② points ③ conclusion")
            sm/*write-record* (fn [_rec] {:summary_uri "at://repo/sum/x"})]
    (let [out (g/invoke sm/GRAPH {:url "https://youtu.be/abc" :lang "ja"})]
      (is (= "Vid" (:title out)))
      (is (re-find #"overview" (:summary out)))
      (is (= "at://repo/sum/x" (:summary_uri out))))))

(deftest summarize-no-transcript-errors
  (binding [sm/*fetch-transcript* (fn [_ _] {:meta {:title "V"} :error "no subtitles available"})]
    (let [out (g/invoke sm/GRAPH {:url "https://youtu.be/abc"})]
      (is (= "no subtitles available" (:error out))))))

(deftest summarize-murakumo-guard
  (testing "off-fleet endpoint refused"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sm/assert-murakumo "https://api.openai.com/v1"))))
  (testing "malformed and lookalike endpoints refused"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sm/assert-murakumo "not-a-url")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sm/assert-murakumo "http://127.0.0.1.attacker.example:4000/v1"))))
  (testing "loopback gateway allowed"
    (is (nil? (sm/assert-murakumo "http://127.0.0.1:4000/v1")))))

(deftest live-authority-requires-explicit-capabilities
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit dump-json capability"
                        (gi/dump-json "https://youtu.be/x")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit fetch-blob capability"
                        (dl/fetch-blob "https://youtu.be/x" "best")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit chat capability"
                        (sm/llm-chat "system" "user")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit process capability"
                        (gi/dump-json-with nil "" "https://youtu.be/x")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit process capability"
                        (dl/fetch-blob-with nil dl/default-config "https://youtu.be/x" "best"))))

(deftest injected-metadata-process-contract
  (let [seen (atom nil)
        result (gi/dump-json-with
                (fn [& args]
                  (reset! seen args)
                  {:exit 0 :out "{\"title\":\"safe\"}" :err ""})
                "/tmp/cookies.txt" "https://youtu.be/x")]
    (is (= "safe" (:title result)))
    (is (= "yt-dlp" (first @seen)))
    (is (some #{"--cookies"} @seen))
    (is (= "https://youtu.be/x" (last @seen)))))

(deftest injected-summary-wire-contract
  (let [seen (atom nil)
        result (sm/llm-chat-with
                (fn [url opts]
                  (reset! seen [url opts])
                  {:status 200
                   :body "{\"choices\":[{\"message\":{\"content\":\"safe\"}}]}"})
                sm/default-config "system" "user")]
    (is (= "safe" result))
    (is (= "http://127.0.0.1:4000/v1/chat/completions" (first @seen)))
    (is (= "application/json" (get-in @seen [1 :headers "Content-Type"])))
    (is (re-find #"max_tokens" (get-in @seen [1 :body])))))
