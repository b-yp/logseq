(ns helper
  (:require [cljs.test :as t :refer [is]]
            [cognitect.transit :as transit]
            [const]
            [datascript.core :as d]
            [frontend.common.missionary-util :as c.m]
            [frontend.worker.rtc.client-op :as client-op]
            [frontend.worker.rtc.core :as rtc.core]
            [frontend.worker.rtc.log-and-state :as rtc-log-and-state]
            [frontend.worker.state :as worker-state]
            [logseq.db :as ldb]
            [logseq.db.frontend.order :as db-order]
            [logseq.outliner.batch-tx :as batch-tx]
            [meander.epsilon :as me]
            [missionary.core :as m]))

(def new-task--upload-example-graph
  (rtc.core/new-task--upload-graph const/test-token const/test-repo const/test-graph-name))

(defn new-task--wait-creating-graph
  [graph-uuid]
  (c.m/backoff
   (take 4 c.m/delays)
   (m/sp
     (let [graphs (m/? (rtc.core/new-task--get-graphs const/test-token))
           graph (some (fn [graph] (when (= graph-uuid (:graph-uuid graph)) graph)) graphs)]
       (when-not graph
         (throw (ex-info "graph not exist" {:graph-uuid graph-uuid})))
       (println "waiting for graph " graph-uuid " finish creating")
       (when (= "creating" (:graph-status graph))
         (throw (ex-info "wait creating-graph" {:missionary/retry true})))))))

(def new-task--clear-all-test-remote-graphs
  (m/sp
    (let [graphs (m/? (rtc.core/new-task--get-graphs const/test-token))
          test-graphs (filter (fn [graph]
                                (not= "deleting" (:graph-status graph)))
                              graphs)]
      (doseq [graph test-graphs]
        (m/? (rtc.core/new-task--delete-graph const/test-token (:graph-uuid graph)))
        (println :deleted-graph (:graph-name graph) (:graph-uuid graph))))))

(def new-task--get-remote-example-graph-uuid
  (c.m/backoff
   (take 5 c.m/delays)
   (m/sp
     (let [graphs (m/? (rtc.core/new-task--get-graphs const/test-token))
           graph
           (some (fn [graph]
                   (when (= const/test-graph-name (:graph-name graph))
                     graph))
                 graphs)]
       (when (= "deleting" (:graph-status graph))
         (throw (ex-info "example graph status is \"deleting\", check server's background-upload-graph log"
                         {:graph-name (:graph-name graph)
                          :graph-uuid (:graph-uuid graph)})))
       (when-not graph
         (throw (ex-info "wait remote-example-graph" {:missionary/retry true
                                                      :graphs graphs})))
       (when (= "creating" (:graph-status graph))
         (throw (ex-info "wait remote-example-graph (creating)" {:missionary/retry true
                                                                 :graphs graphs})))
       (:graph-uuid graph)))))

(defn new-task--download-graph
  [graph-uuid graph-name]
  (m/sp
    (let [download-info-uuid (m/? (rtc.core/new-task--request-download-graph const/test-token graph-uuid))
          result (m/? (rtc.core/new-task--wait-download-info-ready const/test-token download-info-uuid graph-uuid 60000))
          {:keys [_download-info-uuid
                  download-info-s3-url
                  _download-info-tx-instant
                  _download-info-t
                  _download-info-created-at]} result]
      (when (= result :timeout)
        (throw (ex-info "wait download-info-ready timeout" {})))
      (m/? (rtc.core/new-task--download-graph-from-s3
            graph-uuid graph-name download-info-s3-url)))))

(defn get-downloaded-test-conn
  []
  (worker-state/get-datascript-conn const/downloaded-test-repo))

(defn simplify-client-op
  [client-op]
  #_:clj-kondo/ignore
  (me/find
   client-op
    [?op-type _ {:block-uuid ?block-uuid :av-coll [[!a !v _ !add] ...]}]
    [?op-type ?block-uuid (map vector !a !v !add)]

    [?op-type _ {:block-uuid ?block-uuid}]
    [?op-type ?block-uuid]))

(defn new-task--wait-all-client-ops-sent
  [& {:keys [timeout] :or {timeout 10000}}]
  (m/sp
    (let [r (m/? (m/timeout
                  (m/reduce (fn [_ v]
                              (when (and (= :rtc.log/push-local-update (:type v))
                                         (empty? (client-op/get-all-ops const/downloaded-test-repo)))
                                (is (nil? (:ex-data v)))
                                (reduced v)))
                            rtc-log-and-state/rtc-log-flow)
                  timeout :timeout))]
      (is (not= :timeout r)))))

(defn new-task--send-message-to-other-client
  [message]
  (m/sp
    (let [conn (get-downloaded-test-conn)
          _ (assert (some? conn))
          content-page-id (:db/id (ldb/get-page @conn "contents"))
          _ (assert content-page-id)
          sorted-blocks (ldb/sort-by-order (ldb/get-page-blocks @conn content-page-id))
          min-order (db-order/gen-key nil (:block/order (first sorted-blocks)))
          tx-data [{:block/uuid (random-uuid)
                    :block/parent content-page-id
                    :block/order min-order
                    :block/title (transit/write (transit/writer :json) message)
                    :block/page content-page-id
                    :block/format :markdown
                    :block/updated-at 1724836490810
                    :block/created-at 1724836490810}]]
      (batch-tx/with-batch-tx-mode conn {:e2e-test const/downloaded-test-repo :skip-store-conn true}
        (d/transact! conn tx-data))
      (m/? (new-task--wait-all-client-ops-sent)))))

(defn new-task--wait-message-from-other-client
  "Return a task that return message from other client"
  [block-title-pred-fn & {:keys [retry-message retry-count] :or {retry-count 4}}]
  (c.m/backoff
   (take retry-count c.m/delays)
   (m/sp
     (let [conn (get-downloaded-test-conn)
           _ (assert (some? conn))
           content-page-id (:db/id (ldb/get-page @conn "contents"))
           _ (assert content-page-id)
           first-block (first (ldb/sort-by-order (ldb/get-page-blocks @conn content-page-id)))
           first-block-title (some->> (:block/title first-block) (transit/read (transit/reader :json)))]
       (when-not (and (some? first-block-title)
                      (block-title-pred-fn first-block-title))
         (throw (ex-info (str "wait message from other client " retry-message) {:missionary/retry true})))
       first-block-title))))
