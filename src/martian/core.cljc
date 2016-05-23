(ns martian.core
  (:require [tripod.path :as tp]
            [tripod.context :as tc]
            [clojure.string :as string]
            [clojure.walk :refer [keywordize-keys]]
            [schema.core :as s]
            [martian.protocols :refer [Martian url-for request-for]]))

(defn- coerce-params [params data]
  (->> (for [[k {:keys [schema required?]}] params
             :let [value (get data k)]]
         [k (cond
              (and required? (nil? value))
              (let [message (str "Value required for " k)]
                #?(:clj (throw (Exception. message))
                   :cljs (throw (js/Error. message))))

              (nil? value)
              nil

              schema
              (s/validate schema value)

              :else
              value)])
       (remove (comp nil? second))
       (into {})))

(defn- make-interceptors [uri method swagger-definition]
  [{:name ::method
    :leave (fn [{:keys [response] :as ctx}]
             (update ctx :response assoc :method method))}

   {:name ::uri
    :leave (fn [{:keys [request response path-for handler] :as ctx}]
             (let [path-params (:path-params handler)]
               (update ctx :response
                       assoc :uri (path-for (:route-name handler)
                                            (coerce-params path-params (:params request))))))}

   {:name ::query-params
    :leave (fn [{:keys [request response handler] :as ctx}]
             (let [query-params (:query-params handler)
                   coerced-params (coerce-params query-params (:params request))]
               (if (not-empty coerced-params)
                 (update ctx :response assoc :query-params coerced-params)
                 ctx)))}

   {:name ::body-params
    :leave (fn [{:keys [request response handler] :as ctx}]
             (if-let [body-param (get (:params request) (:body-param handler))]
               (update ctx :response assoc :body body-param)
               ctx))}])

(defn- sanitise [x]
  (if (string? x)
    (string/replace-first x "/" "")
    ;; consistent across clj and cljs
    (-> (str x)
        (string/replace-first ":" "")
        (string/replace-first "/" ""))))

(defn- make-schema [swagger-param]
  (cond
    (:enum swagger-param)
    (apply s/enum (:enum swagger-param))

    (= "string" (:type swagger-param))
    s/Str

    (= "integer" (:type swagger-param))
    s/Int

    :default
    nil))

(defn- body-param [swagger-params]
  (when-let [body-param (first (filter #(= "body" (:in %)) swagger-params))]
    (keyword (string/lower-case (:name body-param)))))

(defn- path-params [swagger-params]
  (when-let [path-params (not-empty (filter #(= "path" (:in %)) swagger-params))]
    (reduce (fn [pps param]
              (assoc pps
                     (keyword (string/lower-case (:name param)))
                     {:required? (:required param)
                      :schema (make-schema param)}))
            {}
            path-params)))

(defn- query-params [swagger-params]
  (when-let [query-params (not-empty (filter #(= "query" (:in %)) swagger-params))]
    (reduce (fn [qps param]
              (assoc qps
                     (keyword (string/lower-case (:name param)))
                     {:required? (:required param)
                      :schema (make-schema param)}))
            {}
            query-params)))

(defn- ->tripod-route [url-pattern [method swagger-definition]]
  (let [url-pattern (sanitise url-pattern)
        trailing-slash? (re-find #"/$" url-pattern)
        path-parts (as->
                       (string/split url-pattern #"/") pp
                     (mapv (fn [part]
                             (if-let [[_ token] (re-matches #"\{(.*)\}" part)]
                               (keyword token)
                               part)) pp)
                     (into [""] pp)
                     (concat pp (when trailing-slash? [""])))
        uri (string/join "/" (map str path-parts))]
    {:path uri
     :path-parts path-parts
     :interceptors (make-interceptors uri method swagger-definition)
     :path-params (path-params (:parameters swagger-definition))
     :query-params (query-params (:parameters swagger-definition))
     :body-param (body-param (:parameters swagger-definition))
     ;; todo path constraints - required?
     ;; :path-constraints {:id "(\\d+)"},
     ;; {:in "path", :name "id", :description "", :required true, :type "string", :format "uuid"
     :route-name (keyword (some swagger-definition [:operationId "operationId"]))}))

(defn- swagger->tripod [swagger-json]
  (reduce-kv
   (fn [tripod-routes url-pattern swagger-handlers]
     (into tripod-routes (map (partial ->tripod-route url-pattern) swagger-handlers)))
   []
   (some swagger-json [:paths "paths"])))

(defn- build-instance [api-root swagger-json]
  (let [tripod (swagger->tripod swagger-json)
        path-for (tp/path-for-routes tripod)]
    (reify Martian
      (url-for [this route-name] (url-for this route-name {}))
      (url-for [this route-name params]
        (str api-root (apply path-for (keyword route-name) [(keywordize-keys params)])))

      (request-for [this route-name] (request-for this route-name {}))
      (request-for [this route-name params]
        (when-let [handler (first (filter #(= route-name (:route-name %)) tripod))]
          (let [ctx (tc/enqueue* {} (:interceptors handler))]
            (:response (tc/execute
                        (assoc ctx
                               :path-for (comp (partial str api-root) path-for)
                               :request {:params params}
                               :handler handler)))))))))

(defn bootstrap
  "Creates a routing function which should be supplied with an api-root and a swagger spec

   (let [url-for (bootstrap \"https://api.org\" swagger-spec)]
     (url-for :load-pet {:id 123}))

   ;; => https://api.org/pets/123"
  [api-root swagger-json]
  (build-instance api-root swagger-json))
