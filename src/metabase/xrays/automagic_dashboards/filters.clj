(ns metabase.xrays.automagic-dashboards.filters
  (:require
   [metabase.legacy-mbql.normalize :as mbql.normalize]
   [metabase.legacy-mbql.util :as mbql.u]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [metabase.warehouse-schema.models.field :as field]
   [metabase.xrays.automagic-dashboards.util :as magic.util]
   [toucan2.core :as t2]))

(defn- temporal?
  "Does `field` represent a temporal value, i.e. a date, time, or datetime?"
  [{base-type :base_type, effective-type :effective_type, unit :unit}]
  ;; TODO -- not sure why we're excluding year here? Is it because we normally returned it as an integer in the past?
  (and (not ((disj u.date/extract-units :year) unit))
       (isa? (or effective-type base-type) :type/Temporal)))

(defn- interestingness
  [{base-type :base_type, effective-type :effective_type, semantic-type :semantic_type, :keys [fingerprint]}]
  (cond-> 0
    (some-> fingerprint :global :distinct-count (< 10)) inc
    (some-> fingerprint :global :distinct-count (> 20)) dec
    ((descendants :type/Category) semantic-type)        inc
    (isa? (or effective-type base-type) :type/Temporal) inc
    ((descendants :type/Temporal) semantic-type)        inc
    (isa? semantic-type :type/CreationTimestamp)        inc
    (#{:type/State :type/Country} semantic-type)        inc))

(defn- interleave-all
  [& colls]
  (lazy-seq
   (when (seq colls)
     (concat (map first colls) (apply interleave-all (keep (comp seq rest) colls))))))

(defn- sort-by-interestingness
  [fields]
  (->> fields
       (map #(assoc % :interestingness (interestingness %)))
       (sort-by :interestingness >)
       (partition-by :interestingness)
       (mapcat (fn [fields]
                 (->> fields
                      (group-by (juxt :base_type :semantic_type))
                      vals
                      (apply interleave-all))))))

(defn interesting-fields
  "Pick out interesting fields and sort them by interestingness."
  [fields]
  (->> fields
       (filter (fn [{:keys [base_type effective_type semantic_type] :as field}]
                 (or (temporal? field)
                     (isa? (or effective_type base_type) :type/Boolean)
                     (isa? semantic_type :type/Category))))
       sort-by-interestingness))

(defn- build-fk-map
  [fks field]
  (if (:id field)
    (->> fks
         (filter (comp #{(:table_id field)} :table_id :target))
         (group-by :table_id)
         (keep (fn [[_ [fk & fks]]]
                 ;; Bail out if there is more than one FK from the same table
                 (when (empty? fks)
                   [(:table_id fk) [:field (u/the-id field) {:source-field (u/the-id fk)}]])))
         (into {(:table_id field) [:field (u/the-id field) nil]}))
    (constantly [:field (:name field) {:base-type (:base_type field)}])))

(defn- filter-for-card
  [card field]
  (when-let [field-ref ((:fk-map field) (:table_id card))]
    [:dimension field-ref {:stage-number 0}]))

(defn- add-filter
  [dashcard filter-id field]
  (let [mappings (->> (conj (:series dashcard) (:card dashcard))
                      (keep (fn [card]
                              (when-let [target (filter-for-card card field)]
                                {:parameter_id filter-id
                                 :target       target
                                 :card_id      (:id card)})))
                      not-empty)]
    (cond
      (nil? (:card dashcard)) dashcard
      mappings                (update dashcard :parameter_mappings concat mappings))))

(defn- filter-type-info
  "Return parameter type and section id for a given field."
  [{:keys [effective_type semantic_type] :as _field}]
  (cond
    (or (isa? effective_type :type/Date) (isa? effective_type :type/DateTime))
    {:type "date/all-options"
     :sectionId "date"}

    (or (isa? effective_type :type/Text) (isa? effective_type :type/TextLike))
    {:type "string/="
     :sectionId (if (isa? semantic_type :type/Address) "location" "string")}

    (isa? effective_type :type/Number)
    (if (or (isa? semantic_type :type/PK) (isa? semantic_type :type/FK))
      {:type "id"
       :sectionId "id"}
      {:type "number/="
       :sectionId "number"})

    ;; TODO this needs to be `boolean/=` once we introduce boolean parameters in #57435
    (isa? effective_type :type/Boolean)
    {:type "string/="
     :sectionId "string"}))

(def ^:private ^{:arglists '([dimensions])} remove-unqualified
  (partial remove (fn [{:keys [fingerprint]}]
                    (some-> fingerprint :global :distinct-count (< 2)))))

(defn add-filters
  "Add up to `max-filters` filters to dashboard `dashboard`. The `dimensions` argument is a list of fields for which to
  create filters."
  [dashboard dimensions max-filters]
  (let [fks (when-let [table-ids (not-empty (set (keep (comp :table_id :card)
                                                       (:dashcards dashboard))))]
              (field/with-targets (t2/select :model/Field
                                             :fk_target_field_id [:not= nil]
                                             :table_id [:in table-ids])))]
    (->> dimensions
         remove-unqualified
         sort-by-interestingness
         (take max-filters)
         (reduce
          (fn [dashboard candidate]
            (let [filter-id     (magic.util/filter-id-for-field candidate)
                  candidate     (assoc candidate :fk-map (build-fk-map fks candidate))
                  dashcards     (:dashcards dashboard)
                  dashcards-new (keep #(add-filter % filter-id candidate) dashcards)
                  filter-info   (filter-type-info candidate)]
              ;; Only add filters that apply to all cards and when we have a parameter type for the field
              (if (and (= (count dashcards) (count dashcards-new)) (some? filter-info))
                (-> dashboard
                    (assoc :dashcards dashcards-new)
                    (update :parameters conj (merge {:id   filter-id
                                                     :name (:display_name candidate)
                                                     :slug (:name candidate)}
                                                    filter-info)))
                dashboard)))
          dashboard))))

(defn- flatten-filter-clause
  "Returns a sequence of filter subclauses making up `filter-clause` by flattening `:and` compound filters.

    (flatten-filter-clause [:and
                            [:= [:field 1 nil] 2]
                            [:and
                             [:= [:field 3 nil] 4]
                             [:= [:field 5 nil] 6]]])
    ;; -> ([:= [:field 1 nil] 2]
           [:= [:field 3 nil] 4]
           [:= [:field 5 nil] 6])"
  [[clause-name, :as filter-clause]]
  (when (seq filter-clause)
    (if (= clause-name :and)
      (rest (mbql.u/simplify-compound-filter filter-clause))
      [filter-clause])))

(defn inject-refinement
  "Inject a filter refinement into an MBQL filter clause, returning a new filter clause.

  There are two reasons why we want to do this: 1) to reduce visual noise when we display applied filters; and 2) some
  DBs don't do this optimization or even protest (eg. GA) if there are duplicate clauses.

  Assumes that any refinement sub-clauses referencing fields that are also referenced in the main clause are subsets
  of the latter. Therefore we can rewrite the combined clause to ommit the more broad version from the main clause.
  Assumes both filter clauses can be flattened by recursively merging `:and` claueses
  (ie. no `:and`s inside `:or` or `:not`)."
  [filter-clause refinement]
  (let [in-refinement?   (into #{}
                               (map magic.util/collect-field-references)
                               (flatten-filter-clause refinement))
        existing-filters (->> filter-clause
                              flatten-filter-clause
                              (remove (comp in-refinement? magic.util/collect-field-references)))]
    (if (seq existing-filters)
      ;; since the filters are programatically generated they won't have passed thru normalization, so make sure we
      ;; normalize them before passing them to `combine-filter-clauses`, which validates its input
      (apply mbql.u/combine-filter-clauses (map (partial mbql.normalize/normalize-fragment [:query :filter])
                                                (cons refinement existing-filters)))
      refinement)))
