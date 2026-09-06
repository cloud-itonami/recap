(ns recap-frontend.app-test
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [recap-frontend.app :as app]))

(deftest init-db-sets-expected-defaults
  (testing ":init-db populates app-db with the ported Svelte scaffold data"
    (rf/dispatch-sync [:init-db])
    (is (= app/default-db @rf-db/app-db))
    (is (= "Ai etzhayyim Project Recap" @(rf/subscribe [:app/title])))
    (is (= "etzhayyim-project-recap" @(rf/subscribe [:app/project])))
    (is (= "cloudflare surface" @(rf/subscribe [:app/kind])))
    (is (= 0 @(rf/subscribe [:app/route-count])))
    (is (= [] @(rf/subscribe [:app/routes])))
    (is (= [] @(rf/subscribe [:app/vars])))
    (is (true? @(rf/subscribe [:app/xrpc?])))))

(deftest subs-project-their-own-field-only
  (testing "each sub reads exactly its own key out of app-db"
    (reset! rf-db/app-db {:title "t" :project "p" :name "n" :kind "k"
                           :route-count 3 :routes ["a"] :vars ["V"] :xrpc? false
                           :relative-path "x" :unrelated-key :ignored})
    (is (= "t" @(rf/subscribe [:app/title])))
    (is (= "p" @(rf/subscribe [:app/project])))
    (is (= 3 @(rf/subscribe [:app/route-count])))
    (is (= ["a"] @(rf/subscribe [:app/routes])))
    (is (= ["V"] @(rf/subscribe [:app/vars])))
    (is (false? @(rf/subscribe [:app/xrpc?])))
    (is (= "x" @(rf/subscribe [:app/relative-path])))))
