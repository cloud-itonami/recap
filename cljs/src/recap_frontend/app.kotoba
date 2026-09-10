(ns recap-frontend.app
  "cloud-itonami/recap appview frontend shell.

  Faithful ClojureScript replacement of the former SvelteKit page
  (`svelte/src/routes/+page.svelte`) — a static info card for this Cloudflare
  surface: a header (kind/title/name), a facts grid (Project / Routes /
  XRPC), a Public Routes panel, a Runtime Bindings panel, and a Source panel.
  This ports the same static data the Svelte scaffold hard-coded; it does not
  wire in the real route/var counts from wrangler.jsonc, because the Svelte
  scaffold never did either (routes/vars were literal empty vectors there
  too). Built on this workspace's standard stack (reagent + re-frame +
  jp-go-dds) instead of SvelteKit, per ADR-2608260900."
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [jp-go-dds.core :as dds]))

;; -- app-db ------------------------------------------------------------
;;
;; Ported verbatim from the `app` object literal in
;; svelte/src/routes/+page.svelte, with one exception: `:relative-path` is
;; updated to point at this file (the self-referential "Source" field must
;; describe where its own source now lives; every other field is copied
;; unchanged, including the empty :routes / :vars vectors the Svelte
;; scaffold never wired to real wrangler.jsonc data).

(def default-db
  {:title "Ai etzhayyim Project Recap"
   :project "etzhayyim-project-recap"
   :name "etzhayyim-project-recap"
   :kind "cloudflare surface"
   :route-count 0
   :routes []
   :vars []
   :xrpc? true
   :relative-path "cljs/src/recap_frontend/app.cljs"})

;; -- events --------------------------------------------------------------

(rf/reg-event-db
 :init-db
 (fn [_ _] default-db))

;; -- subs ------------------------------------------------------------------

(rf/reg-sub :app/title (fn [db _] (:title db)))
(rf/reg-sub :app/project (fn [db _] (:project db)))
(rf/reg-sub :app/name (fn [db _] (:name db)))
(rf/reg-sub :app/kind (fn [db _] (:kind db)))
(rf/reg-sub :app/route-count (fn [db _] (:route-count db)))
(rf/reg-sub :app/routes (fn [db _] (:routes db)))
(rf/reg-sub :app/vars (fn [db _] (:vars db)))
(rf/reg-sub :app/xrpc? (fn [db _] (:xrpc? db)))
(rf/reg-sub :app/relative-path (fn [db _] (:relative-path db)))

;; -- view --------------------------------------------------------------

(defn- header []
  (let [kind @(rf/subscribe [:app/kind])
        title @(rf/subscribe [:app/title])
        name @(rf/subscribe [:app/name])]
    [:div
     [:p "Cloudflare " kind]
     [dds/heading 1 title]
     [:span name]]))

(defn- facts-grid []
  (let [project @(rf/subscribe [:app/project])
        route-count @(rf/subscribe [:app/route-count])
        xrpc? @(rf/subscribe [:app/xrpc?])]
    [dds/grid {:min "160px"}
     [dds/card [:span "Project"] [:strong project]]
     [dds/card [:span "Routes"] [:strong (str route-count)]]
     [dds/card [:span "XRPC"] [:strong (if xrpc? "enabled" "not configured")]]]))

(defn- public-routes-panel []
  (let [routes @(rf/subscribe [:app/routes])]
    [dds/section {:title "Public Routes"}
     (if (seq routes)
       (into [:ul] (map (fn [route] [:li route]) routes))
       [:p "No public route is declared next to this app surface."])]))

(defn- runtime-bindings-panel []
  (let [vars @(rf/subscribe [:app/vars])]
    [dds/section {:title "Runtime Bindings"}
     (if (seq vars)
       [dds/row (map (fn [k] ^{:key k} [dds/chip-label k]) vars)]
       [:p "No public vars are declared in the nearest wrangler config."])]))

(defn- source-panel []
  (let [rel-path @(rf/subscribe [:app/relative-path])]
    [dds/section {:title "Source"}
     [:p rel-path]]))

(defn view []
  [dds/container
   [:div {:class "dds-ext-hero"}
    [header]]
   [facts-grid]
   [public-routes-panel]
   [runtime-bindings-panel]
   [source-panel]])

;; -- mount ---------------------------------------------------------------

(defn main []
  (rf/dispatch-sync [:init-db])
  (rdom/render [view] (.getElementById js/document "app")))
