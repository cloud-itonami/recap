(ns lg-recap.graphs.health
  "recap `health` graph — liveness check.

  Faithful clj port of `lg/lg_recap/graphs/health.py` (ADR-2606280030).
  Topology: START → ping → END. The `ping` node returns {:ok true}."
  (:require [langgraph.graph :as g]))

(defn node-ping [_state] {:ok true})

(defn build
  "Compile the health StateGraph (START → ping → END)."
  []
  (-> (g/state-graph)
      (g/add-node :ping node-ping)
      (g/set-entry-point :ping)
      (g/set-finish-point :ping)
      (g/compile-graph)))

(def GRAPH (build))
