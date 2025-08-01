(ns metabase.request.session
  (:require
   [metabase.api.common
    :as api
    :refer [*current-user* *current-user-id* *current-user-permissions-set* *is-group-manager?* *is-superuser?*]]
   [metabase.permissions.core :as perms]
   [metabase.settings.core :as setting]
   [metabase.users.models.user :as user]
   [metabase.util.i18n :as i18n]
   [toucan2.core :as t2]))

(def ^:private current-user-fields
  (into [:model/User] user/admin-or-self-visible-columns))

(defn- find-user [user-id]
  (when user-id
    (-> (t2/select-one current-user-fields, :id user-id)
        user/add-attributes)))

(def ^:private ^:dynamic *user-local-values-user-id*
  "User ID that we've previous bound [[*user-local-values*]] for. This exists so we can avoid rebinding it in recursive
  calls to [[with-current-user]] if it is already bound, as this can mess things up since things
  like [[metabase.settings.models.setting/set-user-local-value!]] will only update the values for the top-level binding."
  ;; placeholder value so we will end up rebinding [[*user-local-values*]] it if you call
  ;;
  ;;    (with-current-user nil
  ;;      ...)
  ;;
  ::none)

(defn do-with-current-user
  "Impl for [[with-current-user]]."
  [{:keys [metabase-user-id is-superuser? permissions-set user-locale settings is-group-manager?]} thunk]
  (binding [*current-user-id*              metabase-user-id
            i18n/*user-locale*             user-locale
            *is-group-manager?*            (boolean is-group-manager?)
            *is-superuser?*                (boolean is-superuser?)
            *current-user*                 (delay (find-user metabase-user-id))
            *current-user-permissions-set* (delay (or permissions-set (some-> metabase-user-id perms/user-permissions-set)))]
    ;; As mentioned above, do not rebind user-local values to something new, because changes to its value will not be
    ;; propagated to frames further up the stack.
    (letfn [(do-with-user-local-values [thunk]
              (if (= *user-local-values-user-id* metabase-user-id)
                (thunk)
                (setting/with-user-local-values (delay (atom (or settings
                                                                 (user/user-local-settings metabase-user-id))))
                  (binding [*user-local-values-user-id* metabase-user-id]
                    (thunk)))))]
      (do-with-user-local-values
       (fn []
         (perms/with-relevant-permissions-for-user metabase-user-id
           (thunk)))))))

(defn with-current-user-fetch-user-for-id
  "Part of the impl for `with-current-user` -- don't use this directly."
  [current-user-id]
  (when current-user-id
    (t2/select-one [:model/User [:id :metabase-user-id] [:is_superuser :is-superuser?] [:locale :user-locale] :settings]
                   :id current-user-id)))

(defn do-as-admin
  "Execute `thunk` with admin perms."
  [thunk]
  (do-with-current-user
   (merge
    (with-current-user-fetch-user-for-id api/*current-user-id*)
    {:is-superuser? true
     :permissions-set #{"/"}
     :user-locale i18n/*user-locale*})
   thunk))

(defmacro as-admin
  "Execude code in body as an admin user."
  {:style/indent 0}
  [& body]
  `(do-as-admin (^:once fn* [] ~@body)))

(defmacro with-current-user
  "Execute code in body with `current-user-id` bound as the current user. (This is not used in the middleware
  itself but elsewhere where we want to simulate a User context, such as when rendering Pulses or in tests.) "
  {:style/indent :defn}
  [current-user-id & body]
  `(do-with-current-user
    (with-current-user-fetch-user-for-id ~current-user-id)
    (fn [] ~@body)))
