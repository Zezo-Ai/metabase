(ns metabase.util.embed
  "Utility functions for public links and embedding."
  (:require
   [buddy.core.codecs :as codecs]
   [buddy.sign.jwt :as jwt]
   [clojure.string :as str]
   [hiccup.core :refer [html]]
   [metabase.config :as config]
   [metabase.models.setting :as setting :refer [defsetting]]
   [metabase.premium-features.core :as premium-features]
   [metabase.public-settings :as public-settings]
   [metabase.util :as u]
   [metabase.util.i18n :refer [deferred-tru trs tru]]
   [metabase.util.json :as json]
   [ring.util.codec :as codec]))

(set! *warn-on-reflection* true)

;;; --------------------------------------------- PUBLIC LINKS UTIL FNS ----------------------------------------------

(defn- oembed-url
  "Return an oEmbed URL for the `relative-path`.

     (oembed-url \"/x\") -> \"http://localhost:3000/api/public/oembed?url=x&format=json\""
  ^String [^String relative-url]
  (str (public-settings/site-url)
       "/api/public/oembed"
       ;; NOTE: some oEmbed consumers require `url` be the first param???
       "?url=" (codec/url-encode (str (public-settings/site-url) relative-url))
       "&format=json"))

(defn- oembed-link
  "Returns a `<link>` tag for oEmbed support."
  ^String [^String url]
  (html [:link {:rel   "alternate"
                :type  "application/json+oembed"
                :href  (oembed-url url)
                :title "Metabase"}]))

(def ^:private ^:const ^String embedly-meta
  "A `<meta>` tag for `Embed.ly` support."
  (html [:meta {:name "generator", :content "Metabase"}]))

(defn head
  "Returns the `<meta>`/`<link>` tags for an embeddable public page."
  ^String [^String url]
  (str embedly-meta (oembed-link url)))

(defn iframe
  "Return an `<iframe>` HTML fragment to embed a public page."
  ^String [^String url, width height]
  (html [:iframe {:src         url
                  :width       width
                  :height      height
                  :frameborder 0}]))

;;; ----------------------------------------------- EMBEDDING UTIL FNS -----------------------------------------------

(defsetting embedding-secret-key
  (deferred-tru "Secret key used to sign JSON Web Tokens for requests to `/api/embed` endpoints.")
  :encryption :when-encryption-key-set
  :visibility :admin
  :audit :no-value
  :setter (fn [new-value]
            (when (seq new-value)
              (assert (u/hexadecimal-string? new-value)
                      (tru "Invalid embedding-secret-key! Secret key must be a hexadecimal-encoded 256-bit key (i.e., a 64-character string).")))
            (setting/set-value-of-type! :string :embedding-secret-key new-value)))

(defn- jwt-header
  "Parse a JWT `message` and return the header portion."
  [^String message]
  (let [[header] (str/split message #"\.")]
    (json/decode+kw (codecs/bytes->str (codec/base64-decode header)))))

(defn- check-valid-alg
  "Check that the JWT `alg` isn't `none`. `none` is valid per the standard, but for obvious reasons we want to make sure
  our keys are signed. Unfortunately, I don't think there's an easy way to do this with the JWT library we use, so
  manually parse the token and check the alg."
  [^String message]
  (let [{:keys [alg]} (jwt-header message)]
    (when-not alg
      (throw (Exception. (trs "JWT is missing `alg`."))))
    (when (= alg "none")
      (throw (Exception. (trs "JWT `alg` cannot be `none`."))))))

(defn unsign
  "Parse a \"signed\" (base-64 encoded) JWT and return a Clojure representation. Check that the signature is
  valid (i.e., check that it was signed with `embedding-secret-key`) and it's otherwise a valid JWT (e.g., not
  expired), or throw an Exception."
  [^String message]
  (when (seq message)
    (try
      (check-valid-alg message)
      (jwt/unsign message
                  (or (embedding-secret-key)
                      (throw (ex-info (tru "The embedding secret key has not been set.") {:status-code 400})))
                  ;; The library will reject tokens with a created at timestamp in the future, so to account for clock
                  ;; skew tell the library to allow for 60 seconds of leeway
                  {:leeway 60})
      ;; if `jwt/unsign` throws an Exception rethrow it in a format that's friendlier to our API
      (catch Throwable e
        (throw (ex-info (.getMessage e) {:status-code 400}))))))

(defn get-in-unsigned-token-or-throw
  "Find `keyseq` in the `unsigned-token` (a JWT token decoded by `unsign`) or throw a 400."
  [unsigned-token keyseq]
  (or (get-in unsigned-token keyseq)
      (throw (ex-info (tru "Token is missing value for keypath {0}" keyseq) {:status-code 400}))))

(defn maybe-populate-initially-published-at
  "Populate `initially_published_at` if embedding is set to true"
  [{:keys [enable_embedding initially_published_at] :as card-or-dashboard}]
  (cond-> card-or-dashboard
    (and (true? enable_embedding) (nil? initially_published_at))
    (assoc :initially_published_at :%now)))

(defsetting show-static-embed-terms
  (deferred-tru "Check if the static embedding licensing should be hidden in the static embedding flow")
  :type    :boolean
  :default true
  :export? true
  :getter  (fn []
             (if-not (and config/ee-available? (:valid (premium-features/token-status)))
               (setting/get-value-of-type :boolean :show-static-embed-terms)
               false)))

(defsetting show-sdk-embed-terms
  (deferred-tru "Check if admin should see the SDK licensing terms popup")
  :type    :boolean
  :default true
  :can-read-from-env? false
  :doc false
  :export? true)
