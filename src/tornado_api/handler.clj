(ns tornado-api.handler
      (:gen-class)
      (:import com.mchange.v2.c3p0.ComboPooledDataSource)
      (:use compojure.core)
      (:use cheshire.core)
      (:use ring.util.response)
      (:require [compojure.handler :as handler]
                [ring.middleware.json :as middleware]
                [clojure.java.jdbc :as sql]
                [taoensso.carmine :as car :refer (wcar)]
                [clj-statsd :as statsd]
                [compojure.route :as route]
                [taoensso.timbre :as timbre]
                [taoensso.timbre.appenders.core :as appenders]
                [ring.adapter.jetty :refer :all]
                [ring.logger.timbre :as logger.timbre]))

(timbre/merge-config!
  {:level :info
   :appenders {:spit (appenders/spit-appender {:fname "/var/log/tornado-api.log"})}})

(def statsd-prefix "tornado.api.")

(def server1-conn {:pool {} :spec {:host "tornado-redis" :port 6379 :password "tornadoapi" }})

(defmacro wcar* [& body] `(car/wcar server1-conn ~@body))

(let [db-host "tornado-db"
      db-port 3306
      db-name "items"]

  (def db-config {:classname "com.mysql.jdbc.Driver"
                  :subprotocol "mysql"
                  :subname (str "//" db-host ":" db-port "/" db-name)
                  :user "tornado"
                  :password "strongpassword"}))

(defn migrated? []
  (-> (sql/query db-config ["select count(*) as count from information_schema.tables where table_name='items'"])
     first :count pos?))

(def schema
  [[:items
    [:id "varchar(256)"]
    [:type "enum('stock','sold')"]
    [:title "varchar(1024)"]
    [:price "decimal(10,2)"]
    [:text "varchar(1024)"]]])

(defn migrate []
  (when (not (migrated?))
    (doseq [table schema]
     (let [sql (apply sql/create-table-ddl table)]
         (sql/execute! db-config [sql])))))

    (defn uuid [] (str (java.util.UUID/randomUUID)))

    (defn get-all-items []
      (response
          (sql/query db-config ["select * from items"])))

    (defn get-item [id]
      (let [item (response (first (sql/query db-config ["select * from items where id = ?" id])))]
        (cond
          (empty? (item :body)) {:status 404}
          :else item)))

    (defn buy-item [item]
      (let [id (uuid)]
        (sql/db-do-commands db-config
          (let [item (assoc item "id" id)]
            (sql/insert! db-config :items item)
            (statsd/gauge (str statsd-prefix "item.bought.total") (item "price"))))
            (wcar* (car/ping)
              (car/set id (item "title")))
        (get-item id)))

    (defn update-item [id item]
      (sql/db-do-commands db-config
          (let [item (assoc item "id" id)]
            (sql/update! db-config :items ["id=?" id] item)
            (statsd/increment (str statsd-prefix "update.item"))))
        (get-item id))

    (defn sell-item [id]
      (sql/db-do-commands db-config
        (let [item (get-item id)
              price (get-in item [:body :price])
              item_state (get-in item [:body :type])]
          (when-not (= item_state "sold")
             (sql/update! db-config :items { :type "sold"} ["id=?" id])
             (statsd/gauge (str statsd-prefix "item.sold.total") price))))
        (get-item id))

(defroutes app-routes
      (context "/api" [] (defroutes api-routes
        (GET  "/" [] (get-all-items))
        (POST "/" {body :body} (buy-item body))
        (context "/:id" [id] (defroutes api-routes
          (GET    "/" [] (get-item id))
          (PUT    "/" {body :body} (update-item id body))
          (DELETE "/" [] (sell-item id))))))
      (route/not-found "Not Found"))

(def app
      (-> (handler/api app-routes)
        (middleware/wrap-json-body)
        (middleware/wrap-json-response)))

(defn -main []
    (migrate)
    (statsd/setup "localhost" 8125)
    (run-jetty (logger.timbre/wrap-with-logger app {:printer :no-color}) {:port 8080}))
