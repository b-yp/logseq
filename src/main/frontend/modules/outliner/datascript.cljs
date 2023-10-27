(ns frontend.modules.outliner.datascript
  (:require [datascript.core :as d]
            [frontend.db :as db]
            [frontend.modules.outliner.pipeline :as pipelines]
            [frontend.modules.editor.undo-redo :as undo-redo]
            [frontend.state :as state]
            [frontend.config :as config]
            [logseq.graph-parser.util :as gp-util]
            [lambdaisland.glogi :as log]
            [frontend.search :as search]
            [clojure.string :as string]
            [frontend.util :as util]
            [logseq.graph-parser.util.block-ref :as block-ref]
            [logseq.db.frontend.malli-schema :as db-malli-schema]
            [frontend.db.fix :as db-fix]
            [frontend.handler.file-based.property.util :as property-util]
            [cljs.pprint :as pprint]
            [malli.core :as m]))

(defn new-outliner-txs-state [] (atom []))

(defn outliner-txs-state?
  [state]
  (and
   (instance? cljs.core/Atom state)
   (coll? @state)))

(defn- validate-db!
  "Validates the entities that have changed in the given datascript tx-report.
   Validation is only for DB graphs"
  [{:keys [db-after tx-data tx-meta]}]
  (let [changed-ids (->> tx-data (map :e) distinct)
        ent-maps* (->> changed-ids (mapcat #(d/datoms db-after :eavt %)) db-malli-schema/datoms->entity-maps vals)
        ent-maps (vec (db-malli-schema/update-properties-in-ents ent-maps*))
        db-schema (db-malli-schema/update-properties-in-schema db-malli-schema/DB db-after)]
    (js/console.log "changed eids:" changed-ids tx-meta)
    (when-let [errors (->> ent-maps
                           (m/explain db-schema)
                           :errors)]
      (js/console.error "Invalid datascript entities detected amongst changed entity ids:" changed-ids)
      (pprint/pprint {:errors errors})
      (pprint/pprint {:entity-maps ent-maps})
      ;; (js/alert "Invalid DB!")
      )))

(defn after-transact-pipelines
  [repo {:keys [_db-before _db-after _tx-data _tempids tx-meta] :as tx-report}]
  (when-not config/test?
    (pipelines/invoke-hooks tx-report)
    (when (and config/dev?
               (config/db-based-graph? (state/get-current-repo))
                ;; Skip tx with update-tx-ids? because they are immediately followed by the original block tx
               (not (:update-tx-ids? tx-meta)))
      (validate-db! tx-report))

    (when (or (:outliner/transact? tx-meta)
              (:outliner-op tx-meta)
              (:whiteboard/transact? tx-meta))
      (undo-redo/listen-db-changes! tx-report))

    (search/sync-search-indice! repo tx-report)))

(defn- remove-nil-from-transaction
  [txs]
  (some->> (gp-util/remove-nils txs)
           (map (fn [x]
                  (if (map? x)
                    (update-vals x (fn [v]
                                     (if (vector? v)
                                       (remove nil? v)
                                       v)))
                    x)))))

(defn get-tx-id
  [tx-report]
  (get-in tx-report [:tempids :db/current-tx]))

(defn update-block-refs
  [txs opts]
  (if-let [changed (:uuid-changed opts)]
    (let [{:keys [from to]} changed
          from-e (db/entity [:block/uuid from])
          to-e (db/entity [:block/uuid to])
          from-id (:db/id from-e)
          to-id (:db/id to-e)
          from-refs (:block/_refs from-e)
          from-path-refs (:block/_path-refs from-e)
          to-refs (:block/_refs to-e)
          from-refs-txs (mapcat (fn [ref]
                                  (let [id (:db/id ref)]
                                    [[:db/retract id :block/refs from-id]
                                     [:db/add id :block/refs to-id]])) from-refs)
          from-path-refs-txs (mapcat (fn [ref]
                                       (let [id (:db/id ref)]
                                         [[:db/retract id :block/path-refs from-id]
                                          [:db/add id :block/path-refs to-id]])) from-path-refs)
          to-refs-txs (mapcat (fn [ref]
                                (let [id (:db/id ref)
                                      new-content (string/replace (:block/content ref)
                                                                  (block-ref/->block-ref to)
                                                                  (block-ref/->block-ref from))]
                                  [[:db/add id :block/content new-content]])) to-refs)]
      (concat txs from-refs-txs from-path-refs-txs to-refs-txs))
    txs))

(defn replace-ref-with-content
  [txs opts]
  (if (and (= :delete-blocks (:outliner-op opts))
           (empty? (:uuid-changed opts)))
    (let [retracted-block-ids (->> (keep (fn [tx]
                                           (when (and (vector? tx)
                                                      (= :db.fn/retractEntity (first tx)))
                                             (second tx))) txs))
          retracted-blocks (map db/entity retracted-block-ids)
          retracted-tx (->> (for [block retracted-blocks]
                              (let [refs (:block/_refs block)]
                                (map (fn [ref]
                                       (let [id (:db/id ref)
                                             block-content (property-util/remove-properties
                                                            (:block/format block) (:block/content block))
                                             new-content (some-> (:block/content ref)
                                                                 (string/replace (re-pattern (util/format "(?i){{embed \\(\\(%s\\)\\)\\s?}}" (str (:block/uuid block))))
                                                                                 block-content)
                                                                 (string/replace (block-ref/->block-ref (str (:block/uuid block)))
                                                                                 block-content))
                                             tx (cond->
                                                 [[:db/retract (:db/id ref) :block/refs (:db/id block)]
                                                  [:db/retract (:db/id ref) :block/path-refs (:db/id block)]]
                                                  new-content
                                                  (conj [:db/add id :block/content new-content]))
                                             revert-tx (cond->
                                                        [[:db/add (:db/id ref) :block/refs (:db/id block)]
                                                         [:db/add (:db/id ref) :block/path-refs (:db/id block)]]
                                                         (:block/content ref)
                                                         (conj [:db/add id :block/content (:block/content ref)]))]
                                         {:tx tx :revert-tx revert-tx})) refs)))
                            (apply concat))
          retracted-tx' (mapcat :tx retracted-tx)
          revert-tx (mapcat :revert-tx retracted-tx)]
      (when (seq retracted-tx')
        (state/set-state! [:editor/last-replace-ref-content-tx (state/get-current-repo)]
                          {:retracted-block-ids retracted-block-ids
                           :revert-tx revert-tx}))
      (concat txs retracted-tx'))
    txs))

(defn fix-db!
  [{:keys [db-before db-after tx-data]}]
  (let [changed-pages (->> (filter (fn [d] (contains? #{:block/left :block/parent} (:a d))) tx-data)
                           (map :e)
                           distinct
                           (map (fn [id]
                                  (-> (or (d/entity db-after id)
                                          (d/entity db-before id))
                                      :block/page
                                      :db/id)))
                           (remove nil?)
                           (distinct))]
    (doseq [changed-page-id changed-pages]
      (db-fix/fix-page-if-broken! db-after changed-page-id {}))))

(defn transact!
  [txs opts before-editor-cursor]
  (let [repo (state/get-current-repo)
        db-based? (config/db-based-graph? repo)
        txs (map (fn [m]
                   (if (map? m)
                     (dissoc m :block/children :block/meta :block/top? :block/bottom? :block/anchor
                               :block/title :block/body :block/level :block/container :db/other-tx
                               :block/unordered)
                     m)) txs)
        txs (remove-nil-from-transaction txs)
        txs (cond-> txs
              (:uuid-changed opts)
              (update-block-refs opts)

              (and (= :delete-blocks (:outliner-op opts))
                   (empty? (:uuid-changed opts)))
              (replace-ref-with-content opts)

              true
              (distinct))]
    (when (and (seq txs)
               (not (:skip-transact? opts))
               (or db-based?
                   (not (contains? (:file/unlinked-dirs @state/state)
                                   (config/get-repo-dir repo)))))

      ;; (prn :debug "Outliner transact:")
      ;; (frontend.util/pprint {:txs txs :opts opts})

      (try
        (let [repo (get opts :repo (state/get-current-repo))
              rs (db/transact! repo txs (assoc opts :outliner/transact? true))
              tx-id (get-tx-id rs)]
          ;; TODO: disable this when db is stable
          (when (and config/dev? (not util/node-test?)) (fix-db! rs))
          (state/update-state! :history/tx->editor-cursor
                               (fn [m] (assoc m tx-id before-editor-cursor)))

          ;; update the current edit block to include full information
          (when-let [block (state/get-edit-block)]
            (when (and (:block/uuid block) (not (:db/id block)))
              (state/set-state! :editor/block (db/pull [:block/uuid (:block/uuid block)]))))
          rs)
        (catch :default e
          (log/error :exception e)
          (throw e))))))