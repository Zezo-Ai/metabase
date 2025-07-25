(ns metabase.sync.sync-metadata.tables
  "Logic for updating Metabase Table models from metadata fetched from a physical DB."
  (:require
   [clojure.data :as data]
   [clojure.set :as set]
   [medley.core :as m]
   [metabase.driver :as driver]
   [metabase.driver.util :as driver.u]
   [metabase.lib.schema.common :as lib.schema.common]
   [metabase.models.humanization :as humanization]
   [metabase.models.interface :as mi]
   [metabase.sync.fetch-metadata :as fetch-metadata]
   [metabase.sync.interface :as i]
   [metabase.sync.sync-metadata.crufty :as crufty]
   [metabase.sync.sync-metadata.metabase-metadata :as metabase-metadata]
   [metabase.sync.util :as sync-util]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]))

;;; ------------------------------------------------ "Crufty" Tables -------------------------------------------------

;; Crufty tables are ones we know are from frameworks like Rails or Django and thus automatically mark as `:cruft`

(def ^:private crufty-table-patterns
  "Regular expressions that match Tables that should automatically given the `visibility-type` of `:cruft`.
   This means they are automatically hidden to users (but can be unhidden in the admin panel).
   These `Tables` are known to not contain useful data, such as migration or web framework internal tables."
  #{;; Django
    #"^auth_group$"
    #"^auth_group_permissions$"
    #"^auth_permission$"
    #"^django_admin_log$"
    #"^django_content_type$"
    #"^django_migrations$"
    #"^django_session$"
    #"^django_site$"
    #"^south_migrationhistory$"
    #"^user_groups$"
    #"^user_user_permissions$"
    ;; Drupal
    #".*_cache$"
    #".*_revision$"
    #"^advagg_.*"
    #"^apachesolr_.*"
    #"^authmap$"
    #"^autoload_registry.*"
    #"^batch$"
    #"^blocked_ips$"
    #"^cache.*"
    #"^captcha_.*"
    #"^config$"
    #"^field_revision_.*"
    #"^flood$"
    #"^node_revision.*"
    #"^queue$"
    #"^rate_bot_.*"
    #"^registry.*"
    #"^router.*"
    #"^semaphore$"
    #"^sequences$"
    #"^sessions$"
    #"^watchdog$"
    ;; Rails / Active Record
    #"^schema_migrations$"
    #"^ar_internal_metadata$"
    ;; PostGIS
    #"^spatial_ref_sys$"
    ;; nginx
    #"^nginx_access_log$"
    ;; Liquibase
    #"^databasechangelog$"
    #"^databasechangeloglock$"
    ;; Lobos
    #"^lobos_migrations$"
    ;; MSSQL
    #"^syncobj_0x.*"})

;;; ---------------------------------------------------- Syncing -----------------------------------------------------

(mu/defn- update-database-metadata!
  "If there is a version in the db-metadata update the DB to have that in the DB model"
  [database    :- i/DatabaseInstance
   db-metadata :- i/DatabaseMetadata]
  (log/infof "Found new version for DB: %s" (:version db-metadata))
  (t2/update! :model/Database (u/the-id database)
              {:details
               (assoc (:details database) :version (:version db-metadata))}))

(mu/defn- cruft-dependent-cols [{table-name :name :as table}
                                database
                                sync-stage :- [:enum ::reactivate ::create ::update]]
  (let [is-crufty? (if (and (= sync-stage ::update)
                            (not (:is_attached_dwh database)))
                     ;; TODO: we should add an updated_by column to metabase_table in
                     ;; [[metabase.warehouse-schema.api.table/update-table!*]] to track occasions where the table was
                     ;; updated by an admin, and respect their choices during an update.
                     ;;
                     ;; This will fix the issue where a table is marked as visible, but cruftiness settings keep re-hiding it
                     ;; during update steps. This is also how we handled this before the addition of auto-cruft-tables.
                     ;;
                     ;; Setting it false will be a no-op because we never unhide tables that are already visible via cruft settings.
                     ;;
                     ;; More context: https://metaboat.slack.com/archives/C013N8XL286/p1743707103874849
                     false
                     (crufty/name? table-name (into crufty-table-patterns
                                                    (some-> database :settings :auto-cruft-tables))))]
    {:initial_sync_status (cond
                            ;; if we're updating a table, we don't overwrite the initial sync status, so that it remain
                            ;; "complete" during the sync. See:
                            ;; [[metabase.sync.util-test/initial-sync-status-table-only-test]]
                            (= sync-stage ::update) (:initial_sync_status table)
                            is-crufty?              "complete"
                            :else                   "incomplete")
     :visibility_type     (when is-crufty? :cruft)}))

(defn create-table!
  "Creates a new table in the database, ready to be synced.
   Throws an exception if there is already a table with the same name, schema and database ID."
  [database table]
  (t2/insert-returning-instance!
   :model/Table
   (merge (cruft-dependent-cols table database ::create)
          {:active                  true
           :db_id                   (:id database)
           :schema                  (:schema table)
           :description             (:description table)
           :database_require_filter (:database_require_filter table)
           :display_name            (or (:display_name table)
                                        (humanization/name->human-readable-name (:name table)))
           :name                    (:name table)})))

(defn create-or-reactivate-table!
  "Create a single new table in the database, or mark it as active if it already exists."
  [database {schema :schema table-name :name :as table}]
  (if-let [existing-id (t2/select-one-pk :model/Table
                                         :db_id (u/the-id database)
                                         :schema schema
                                         :name table-name
                                         :active false)]
    (let [table (t2/select-one :model/Table existing-id)]
      ;; if the table already exists but is marked *inactive*, mark it as *active*
      (t2/update! :model/Table existing-id (cond-> (cruft-dependent-cols table database ::reactivate)

                                             ;; do not unhide tables w/ cruft settings
                                             (some? (:visibility_type table))
                                             (dissoc :visibility_type)

                                             true
                                             (assoc :active true))))
    ;; otherwise create a new Table
    (create-table! database table)))

;; TODO - should we make this logic case-insensitive like it is for fields?

(mu/defn- create-or-reactivate-tables!
  "Create `new-tables` for database, or if they already exist, mark them as active."
  [database :- i/DatabaseInstance
   new-table-metadatas :- [:set i/DatabaseMetadataTable]]
  (doseq [table-metadata new-table-metadatas]
    (log/info "Found new table:"
              (sync-util/name-for-logging (mi/instance :model/Table table-metadata))))
  (doseq [table-metadata new-table-metadatas]
    (create-or-reactivate-table! database table-metadata)))

(mu/defn- retire-tables!
  "Mark any `old-tables` belonging to `database` as inactive."
  [database   :- i/DatabaseInstance
   old-tables :- [:set [:map
                        [:name ::lib.schema.common/non-blank-string]
                        [:schema [:maybe ::lib.schema.common/non-blank-string]]]]]
  (log/info "Marking tables as inactive:"
            (for [table old-tables]
              (sync-util/name-for-logging (mi/instance :model/Table table))))
  (doseq [{schema :schema table-name :name :as _table} old-tables]
    (t2/update! :model/Table {:db_id  (u/the-id database)
                              :schema schema
                              :name   table-name
                              :active true}
                {:active false})))

(def ^:private keys-to-update
  [:description :database_require_filter :estimated_row_count :visibility_type :initial_sync_status])

(mu/defn- update-table-metadata-if-needed!
  "Update the table metadata if it has changed."
  [table-metadata :- i/DatabaseMetadataTable
   metabase-table :- (ms/InstanceOf :model/Table)
   metabase-database :- (ms/InstanceOf :model/Database)]
  (log/infof "Updating table metadata for %s" (sync-util/name-for-logging metabase-table))
  (let [old-table               (select-keys metabase-table keys-to-update)
        new-table               (-> (zipmap keys-to-update (repeat nil))
                                    (merge table-metadata
                                           (cruft-dependent-cols metabase-table metabase-database
                                                                 ::update))
                                    (select-keys keys-to-update))
        [_ changes _]           (data/diff old-table new-table)
        changes                 (cond-> changes
                                  ;; we only update the description if the initial state is nil
                                  ;; because don't want to override the user edited description if it exists:
                                  (some? (:description old-table))
                                  (dissoc changes :description)

                                  (or
                                   ;; don't unhide tables that were hidden w/ cruft settings
                                   (some? (:visibility_type old-table))
                                   ;; noop
                                   (= (:visibility_type new-table) (:visibility_type old-table)))
                                  (dissoc changes :visibility_type))]
    (doseq [[k v] changes]
      (log/infof "%s of %s changed from %s to %s"
                 k
                 (sync-util/name-for-logging metabase-table)
                 (get metabase-table k)
                 v))
    (when (seq changes)
      (t2/update! :model/Table (:id metabase-table) changes))))

(mu/defn- update-tables-metadata-if-needed!
  [table-metadatas :- [:set i/DatabaseMetadataTable]
   metabase-tables :- [:set (ms/InstanceOf :model/Table)]
   metabase-database :- (ms/InstanceOf :model/Database)]
  (let [name+schema->table-metadata (m/index-by (juxt :name :schema) table-metadatas)
        name+schema->metabase-table (m/index-by (juxt :name :schema) metabase-tables)]
    (doseq [name+schema (set/intersection (set (keys name+schema->table-metadata)) (set (keys name+schema->metabase-table)))]
      (update-table-metadata-if-needed! (name+schema->table-metadata name+schema)
                                        (name+schema->metabase-table name+schema)
                                        metabase-database))))

(mu/defn- table-set :- [:set i/DatabaseMetadataTable]
  "So there exist tables for the user and metabase metadata tables for internal usage by metabase.
  Get set of user tables only, excluding metabase metadata tables."
  [db-metadata :- i/DatabaseMetadata]
  (into #{}
        (remove metabase-metadata/is-metabase-metadata-table?)
        (:tables db-metadata)))

(mu/defn- db->our-metadata :- [:set (ms/InstanceOf :model/Table)]
  "Return information about what Tables we have for this DB in the Metabase application DB."
  [database :- i/DatabaseInstance]
  (set (t2/select [:model/Table :id :name :schema :description :database_require_filter :estimated_row_count
                   :visibility_type :initial_sync_status]
                  :db_id  (u/the-id database)
                  :active true)))

(mu/defn- adjusted-schemas :- [:maybe [:map-of :string :string]]
  "Returns a map of schemas that should be adjusted to their new names."
  [driver
   database
   our-metadata :- [:set (ms/InstanceOf :model/Table)]]
  (reduce
   (fn [accum schema]
     (let [new-schema (driver/adjust-schema-qualification driver database schema)]
       (cond-> accum
         (not= schema new-schema) (assoc schema new-schema))))
   nil
   (into #{} (map :schema our-metadata))))

(defn- adjust-table-schemas!
  [database schemas-to-update]
  (when schemas-to-update
    (log/infof "Renaming schemas: %s" (pr-str schemas-to-update)))
  (doseq [[schema new-schema] schemas-to-update]
    (t2/update! :model/Table
                :db_id (:id database)
                :schema schema
                {:schema new-schema})))

(mu/defn sync-tables-and-database!
  "Sync the Tables recorded in the Metabase application database with the ones obtained by calling `database`'s driver's
  implementation of `describe-database`.
  Also syncs the database metadata taken from describe-database if there is any"
  ([database :- i/DatabaseInstance]
   (sync-tables-and-database! database (fetch-metadata/db-metadata database)))

  ([database :- i/DatabaseInstance db-metadata]
   ;; determine what's changed between what info we have and what's in the DB
   (let [driver (driver.u/database->driver database)
         db-table-metadatas    (table-set db-metadata)
         name+schema           #(select-keys % [:name :schema])
         name+schema->db-table (m/index-by name+schema db-table-metadatas)
         our-metadata          (db->our-metadata database)
         multi-level-support?  (driver.u/supports? driver :multi-level-schema database)
         schemas-to-update     (when multi-level-support?
                                 (adjusted-schemas driver database our-metadata))
         our-metadata          (cond->> our-metadata
                                 multi-level-support?
                                 (into #{} (map (fn [table]
                                                  (update table :schema #(get schemas-to-update %1 %1))))))
         keep-name+schema-set  (fn [metadata]
                                 (set (map name+schema metadata)))
         [new-table-metadatas
          old-table-metadatas] (data/diff
                                (keep-name+schema-set (set (map name+schema db-table-metadatas)))
                                (keep-name+schema-set (set (map name+schema our-metadata))))]
     (sync-util/with-error-handling (format "Error updating table schemas for %s"
                                            (sync-util/name-for-logging database))
       (adjust-table-schemas! database schemas-to-update))

     ;; update database metadata from database
     (when (some? (:version db-metadata))
       (sync-util/with-error-handling (format "Error creating/reactivating tables for %s"
                                              (sync-util/name-for-logging database))
         (update-database-metadata! database db-metadata)))
     ;; create new tables as needed or mark them as active again
     (when (seq new-table-metadatas)
       (let [new-tables-info (set (map #(get name+schema->db-table (name+schema %)) new-table-metadatas))]
         (sync-util/with-error-handling (format "Error creating/reactivating tables for %s"
                                                (sync-util/name-for-logging database))
           (create-or-reactivate-tables! database new-tables-info))))
     ;; mark old tables as inactive
     (when (seq old-table-metadatas)
       (sync-util/with-error-handling (format "Error retiring tables for %s" (sync-util/name-for-logging database))
         (retire-tables! database old-table-metadatas)))

     (sync-util/with-error-handling (format "Error updating table metadata for %s" (sync-util/name-for-logging database))
       ;; we need to fetch the tables again because we might have retired tables in the previous steps
       (update-tables-metadata-if-needed! db-table-metadatas (db->our-metadata database) database))

     {:updated-tables (+ (count new-table-metadatas) (count old-table-metadatas))
      :total-tables   (count our-metadata)})))
