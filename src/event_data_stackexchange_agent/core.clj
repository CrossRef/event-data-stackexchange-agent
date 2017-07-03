(ns event-data-stackexchange-agent.core
  (:require [org.crossref.event-data-agent-framework.core :as c]
            [event-data-common.status :as status]
            [crossref.util.doi :as cr-doi]
            [clojure.tools.logging :as log]
            [clojure.java.io :refer [reader]]
            [clojure.data.json :as json]
            [clj-time.coerce :as coerce]
            [clj-time.core :as clj-time]
            [throttler.core :refer [throttle-fn]]
            [clj-http.client :as client]
            [config.core :refer [env]]
            [robert.bruce :refer [try-try-again]]
            [clj-time.format :as clj-time-format])
  (:import [java.util UUID]
           [org.apache.commons.codec.digest DigestUtils]
           [org.apache.commons.lang3 StringEscapeUtils])
  (:gen-class))

(def source-token "a8affc7d-9395-4f1f-a1fd-d00cfbdfa718")
(def user-agent "CrossrefEventDataBot (eventdata@crossref.org)")
(def license "https://creativecommons.org/licenses/by-sa/4.0/")
(def version (System/getProperty "event-data-stackexchange-agent.version"))
(def api-host "https://api.stackexchange.com")

(def excerpts-filter
  "Stable filter string that specifies the fields we want to get back from excerpt search."
  "!FcbKgRXe3Y.kh-SxIte4x1.ZCx")

(def sites-filter
  "Stable filter string that specifies the fields we want to get back from sites list."
  "!)Qpa1bGM9MgBBV.BJ1yrJ8GF")

; https://api.stackexchange.com/docs/paging
(def page-size 100)

(def date-format
  (:date-time-no-ms clj-time-format/formatters))

(defn fetch-sites-page
  "Fetch an API result, return a page of Sites."
  [page-number]
  (status/send! "stackexchange-agent" "stackexchange" "fetch-sites" 1)
  (log/info "Fetch list of sites")
  (let [url (str api-host "/2.2/sites" )
        query-params {:page page-number
                      :filter sites-filter
                      :pagesize page-size}]
    
    ; If the API returns an error
    (try
      (try-try-again
        {:sleep 30000 :tries 10}
        #(let [result (client/get url {:headers {"User-Agent" user-agent} :query-params query-params :throw-exceptions true})]

          (log/info "Fetched" url query-params)

          (condp = (:status result)
            200 (json/read-str (:body result) :key-fn keyword)
            
            ; Not found? Stop.
            404 {:url url :actions [] :extra {:error "Result not found"}}

            ; Don't throw on this exception, retrying won't help.
            400 {:url url :actions [] :extra {:error "Bad Request, maybe rate limit"}}
            
            (do
              (log/error "Unexpected status code" (:status result) "from" url)
              (log/error "Body of error response:" (:body url))
              (throw (new Exception "Unexpected status"))))))

      (catch Exception ex (do
        (log/error "Error fetching" url)
        (log/error "Exception:" ex)
        {:url url :items [] :extra {:error "Failed to retrieve page"}})))))

(defn fetch-sites
  ([] (fetch-sites 1))
  ([page-number]
    (let [result (fetch-sites-page page-number)
          items (result :items [])]
      (if (result :has_more)
        (lazy-cat items (fetch-sites (inc page-number)))
        items))))

(defn api-item-to-action
  [item site-url]
  (let [typ (:item_type item)
        creation-date (clj-time-format/unparse date-format (coerce/from-long (* 1000 (long (:creation_date item)))))
        question-id (:question_id item)
        answer-id (:answer_id item)
        url (condp = typ
              "question" (str site-url "/q/" question-id)
              "answer" (str site-url "/a/" answer-id)
              nil)

        work-type (condp = typ
              "question" "webpage"
              "answer" "comment"
              nil)

        author-name (-> item :owner :display_name)
        author-id (-> item :owner :user_id)
        author-url (-> item :owner :link)

        ; text comes encoded
        text (StringEscapeUtils/unescapeHtml4 (:body item ""))]

  ; URL only constructed when we get a type we recognise.
  (when url
    {:url url
     :relation-type-id "discusses"
     ; as this comes from the specific API, don't use a general purpose URL as the action id.
     :id (DigestUtils/sha1Hex ^String (str "stackexchange-" url))
     :occurred-at creation-date
     :subj {
       :pid url
       :title (StringEscapeUtils/unescapeHtml4 (:title item ""))
       :issued creation-date
       :type work-type
       :author {:url author-url :name author-name :id author-id}}
     :observations [{:type :plaintext
                    :input-content text
                    :sensitive true}]})))

; API
(defn parse-page
  "Parse response JSON to a page of Actions."
  [url site-url json-data]
  (let [parsed (json/read-str json-data :key-fn keyword)
       items (:items parsed)]
    {:url url
     :extra (select-keys parsed [:quota_remaining :quota_max :backoff])
     :actions (map #(api-item-to-action % site-url) items)}))

(defn fetch-page
  "Fetch the API result, return a page of Actions."
  [site-info domain from-date page-number]
  (status/send! "stackexchange-agent" "stackexchange" "fetch-page" 1)
  (log/info "Fetch page for site" site-info "domain" domain)
  (let [url (str api-host "/2.2/search/excerpts" )
        query-params {:order "desc"
                      :sort "creation"
                      :q (str "url:\"" domain "\"")
                      :fromdate from-date
                      :page page-number
                      :pagesize page-size
                      :site (:api_site_parameter site-info)
                      :filter excerpts-filter}]

    ; If the API returns an error
    (try
      (try-try-again
        {:sleep 30000 :tries 10}
        #(let [result (client/get url {:headers {"User-Agent" user-agent} :query-params query-params :throw-exceptions true})]

          (log/info "Fetched" url query-params)

          (condp = (:status result)
            200 (parse-page url (:site_url site-info) (:body result))
            
            ; Not found? Stop.
            404 {:url url :actions [] :extra {:error "Result not found"}}

            ; Don't throw on this exception, retrying won't help.
            400 {:url url :actions [] :extra {:error "Bad Request, maybe rate limit"}}
            
            (do
              (log/error "Unexpected status code" (:status result) "from" url)
              (log/error "Body of error response:" (:body url))
              (throw (new Exception "Unexpected status"))))))

      (catch Exception ex (do
        (log/error "Error fetching" url)
        (log/error "Exception:" ex)
        {:url url :actions [] :extra {:error "Failed to retrieve page"}})))))

; https://api.stackexchange.com/docs/throttle
; Not entirely predictable. Go ultra low. 
(def fetch-page-throttled (throttle-fn fetch-page 5 :hour))

(defn fetch-pages
  "Lazy sequence of pages for the domain."
  ([site-info domain from-date]
    ; Pagination starts with 1! 
    ; https://api.stackexchange.com/docs/paging
    (fetch-pages site-info domain from-date 1))

  ([site-info domain from-date page-number]
    (log/info "Query page" page-number "of" site-info domain)
    (let [result (fetch-page-throttled site-info domain from-date page-number)
          end (-> result :extra :has_more not)
          ; as float or nil
          quota-remaining-proportion (when (and (-> result :extra :quota_max)
                                       (-> result :extra :quota_remaining)
                                       (> (-> result :extra :quota_max) 0))
                                     (float (/ (-> result :extra :quota_remaining)
                                               (-> result :extra :quota_max))))

          backoff-seconds (-> result :extra :backoff)

                             ; We get 400s on over-quota requests.
          emergency-stop (or (-> result :exta :error)
                             (-> result :extra :quota_remaining (or 0) zero?))]

      (log/info "Quota remaining:" quota-remaining-proportion "," (-> result :extra :quota_remaining) "/" (-> result :extra :quota_max) "quota remaining!")

      ; In last 10% of quota, sleep more between requests.
      (when (and quota-remaining-proportion (< quota-remaining-proportion 0.1))
        (log/info "Warning! " (-> result :extra :quota_remaining) "/" (-> result :extra :quota_max) "quota remaining!")
        (Thread/sleep 20000))

      (when backoff-seconds
        (log/info "Back off for" backoff-seconds "seconds")
        (Thread/sleep (* 1000 backoff-seconds)))

      (when emergency-stop
        (log/error "Out of API quota, stopping before end of results!"))

      (if (or end emergency-stop)
        [result]
        (lazy-seq (cons result (fetch-pages site-info domain from-date (inc page-number))))))))

(defn check-all-sites-from-artifact
  "Check all sites for unseen links."
  [artifacts callback]
  (log/info "Start crawl all Sites from artifact at" (str (clj-time/now)))
  (status/send! "stackexchange-agent" "process" "scan-sites" 1)
  (let [[site-list-url site-list] (get artifacts "stackexchange-sites")
        
        ; Sites artifact is {:site_url :api_site_parameter}
        sites (json/read-str site-list :key-fn keyword)

        num-sites (count sites)
        site-counter (atom 0)
        cutoff-date (-> 10 clj-time/days clj-time/ago)

        ; API takes timestamp.
        from-date (int (/ (coerce/to-long cutoff-date) 1000))]
    
    (doseq [site-info sites]
      (swap! site-counter inc)
      (log/info "Query site" (:site_url site-info) @site-counter "/" num-sites " total progress " (int (* 100 (/ @site-counter num-sites))) "%")

      (let [pages (fetch-pages site-info "doi.org" from-date)]
        
        ; Each page is big, so send them one at once.
        (doseq [page pages]
          (let [evidence-record {:source-token source-token
                                 :source-id "stackexchange"
                                 :license license
                                 :agent {:version version :artifacts {:stackexchange-site-version site-list-url}}
                                 :extra {:cutoff-date (str cutoff-date) :queried-domain "doi.org"}
                                 :pages [page]}]
        (log/info "Sending evidence-record...")
        (callback evidence-record))))))
  (log/info "Finished scan."))

(defn check-all-sites
  "Check all sites (except those in artifact, as we scan those on a more regular basis.)"
  [artifacts callback]
  (log/info "Start crawl all Sites on stackexchange at" (str (clj-time/now)))
  (status/send! "stackexchange-agent" "process" "scan-sites" 1)
  (let [[site-list-url site-list] (get artifacts "stackexchange-sites")
        
        ; Sites artifact is [{:site_url :api_site_parameter}]
        artifact-sites (map #(select-keys % [:site_url :api_site_parameter]) (json/read-str site-list :key-fn keyword))
        all-sites (map #(select-keys % [:site_url :api_site_parameter]) (fetch-sites))

        sites-not-in-artifact (clojure.set/difference (set all-sites) (set artifact-sites))

        num-sites (count sites-not-in-artifact)
        site-counter (atom 0)

        cutoff-date (-> 40 clj-time/days clj-time/ago)

        ; API takes timestamp.
        from-date (int (/ (coerce/to-long cutoff-date) 1000))]
    (log/info "Found" (count artifact-sites) "in artifact, " (count all-sites) "possible available. Scanning difference " num-sites "sites")

    (doseq [site-info sites-not-in-artifact]
      (swap! site-counter inc)
      (log/info "Query site" (:site_url site-info) @site-counter "/" num-sites " total progress " (int (* 100 (/ @site-counter num-sites))) "%")
      (let [pages (fetch-pages site-info "doi.org" from-date)]
        
        ; Each page is big, so send them one at once.
        (doseq [page pages]
          (let [evidence-record {:source-token source-token
                                 :source-id "stackexchange"
                                 :license license
                                 :agent {:version version :artifacts {:stackexchange-site-version site-list-url}}
                                 :extra {:cutoff-date (str cutoff-date) :queried-domain "doi.org"}
                                 :pages [page]}]
        (log/info "Sending evidence-record...")
        (callback evidence-record))))))
  (log/info "Finished scan."))

(def agent-definition
  {:agent-name "stackexchange-agent"
   :version version
   :jwt (:stackexchange-jwt env)
   :schedule [
             {:name "check-sites-from-artifact"
              :seconds 432000 ; wait five days between scans from the small list.
              :fixed-delay true
              :fun check-all-sites-from-artifact
              :required-artifacts ["stackexchange-sites"]}
             {:name "check-all-sites"
              :seconds 2592000 ; wait 30 days between scans of entire data set.
              :fixed-delay true
              :fun check-all-sites
              :required-artifacts ["stackexchange-sites"]}]
   :runners []})

(defn -main [& args]
  (c/run agent-definition))
