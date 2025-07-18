(ns metabase.query-processor.util.persisted-cache
  "TODO -- consider whether this belongs here or if we should move some or all of this code into the `model-persistence`
  module."
  (:require
   [metabase.driver :as driver]
   [metabase.driver.ddl.interface :as ddl.i]
   [metabase.driver.util :as driver.u]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.lib.schema.metadata :as lib.schema.metadata]
   [metabase.model-persistence.core :as model-persistence]
   [metabase.system.core :as system]
   [metabase.util.malli :as mu]))

(mu/defn can-substitute?
  "Taking a card and a persisted-info record (possibly nil), returns whether the card's query can be substituted for a
  persisted version."
  [card           :- ::lib.schema.metadata/card
   persisted-info :- [:maybe ::lib.schema.metadata/persisted-info]]
  (and persisted-info
       (model-persistence/allow-persisted-substitution?)
       (:active persisted-info)
       (= (:state persisted-info) "persisted")
       (:definition persisted-info)
       (:query-hash persisted-info)
       (= (:query-hash persisted-info) (model-persistence/query-hash (:dataset-query card)))
       (= (:definition persisted-info)
          (model-persistence/metadata->definition (:result-metadata card)
                                                  (:table-name persisted-info)))))

(mu/defn persisted-info-native-query
  "Returns a native query that selects from the persisted cached table from `persisted-info`. Does not check if
  persistence is appropriate. Use [[can-substitute?]] for that check."
  [database-id                              :- ::lib.schema.id/database
   {:keys [table-name] :as _persisted-info} :- ::lib.schema.metadata/persisted-info]
  (let [driver (or driver/*driver* (driver.u/database->driver database-id))
        ;; We should not be using specific driver implementations
        quote-name (requiring-resolve 'metabase.driver.sql.util/quote-name)]
    ;; select * because we don't actually know the name of the fields when in the actual query. See #28902
    (format "select * from %s.%s"
            (quote-name
             driver
             :table
             (ddl.i/schema-name {:id database-id} (system/site-uuid)))
            (quote-name
             driver
             :table
             table-name))))
