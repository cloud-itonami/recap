;; lg-recap clj-port test runner (repo rule: run_tests.clj, NOT .sh).
;;
;;   bb run_tests.clj      (from 60-apps/etzhayyim-project-recap/lg-clj/)
;;   bb test               (bb.edn task alias)
;;
;; Exits non-zero if any test fails or errors.
(ns lg-recap.host
  (:require [babashka.http-client :as http]
            [babashka.process :as process]
            [clojure.test :as t]
            [lg-recap.graphs.download :as download]
            [lg-recap.graphs.get-info :as get-info]
            [lg-recap.graphs.summarize :as summarize]
            [lg-recap.server :as server]
            [lg-recap.smoke-test]))

(defn- env [name default] (or (System/getenv name) default))

(def common-config
  {:repo (env "RECAP_REPO_DID" "did:web:recap.etzhayyim.com")
   :owner (env "RECAP_OWNER_DID" "did:web:recap.etzhayyim.com")})

(def download-config
  (merge common-config
         {:default-org-did (env "RECAP_ORG_DID" "anon")
          :cookies-file (env "YTDLP_COOKIES_FILE" "")
          :upload-enabled? (boolean (or (System/getenv "B2_KEY_ID")
                                        (System/getenv "AWS_ACCESS_KEY_ID")))}))

(def summarize-config
  (merge common-config
         {:llm-url (env "VLLM_URL" "http://127.0.0.1:4000/v1")
          :llm-model (env "VLLM_MODEL" "gemma3:4b")
          :llm-timeout-sec (Double/parseDouble (env "VLLM_TIMEOUT_SEC" "120"))}))

(def api-key (env "LG_API_KEY" ""))

(defn dump-json [url]
  (get-info/dump-json-with process/sh (:cookies-file download-config) url))

(defn fetch-blob [url format-id]
  (download/fetch-blob-with process/sh download-config url format-id))

(defn llm-chat [system user]
  (summarize/llm-chat-with http/post summarize-config system user))

(defn with-capabilities [f]
  (binding [download/*config* download-config
            download/*fetch-blob* fetch-blob
            get-info/*dump-json* dump-json
            summarize/*config* summarize-config
            summarize/*llm-chat* llm-chat
            server/*api-key* api-key]
    (f)))

(let [{:keys [fail error]} (t/run-tests 'lg-recap.smoke-test)]
  (when (pos? (+ (or fail 0) (or error 0)))
    (System/exit 1)))
