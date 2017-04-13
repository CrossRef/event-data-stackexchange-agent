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
            [clojure.core.async :refer [>!!]]
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
(def result-filter "!FcbKgRXe3Y.kh-SxIte4x1.ZCx")

; https://api.stackexchange.com/docs/paging
(def page-size 100)

(def date-format
  (:date-time-no-ms clj-time-format/formatters))

(defn api-item-to-action
  [item site-url]
  (let [typ (:item_type item)
        creation-date (clj-time-format/unparse date-format (coerce/from-long (* 1000 (long (:creation_date item)))))
        question-id (:question_id item)
        answer-id (:answer_id item)
        url (condp = typ
              "question" (str site-url "q/" question-id)
              "answer" (str site-url "a/" answer-id)
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
     :id (DigestUtils/sha1Hex ^String url)
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
     :extra {:has-more (:has_more parsed)}
     :actions (map #(api-item-to-action % site-url) items)}))

(defn fetch-page
  "Fetch the API result, return a page of Actions."
  [site-info domain page-number]
  (status/add! "stackexchange-agent" "stackexchange" "fetch-page" 1)
  (log/info "Fetch page for site" site-info "domain" domain)
  (let [url (str api-host "/2.2/search/excerpts" )
        query-params {:order "desc" :sort "creation" :q (str "url:\"" domain "\"") :page page-number :site (:api_site_parameter site-info) :filter result-filter}]

    ; If the API returns an error
    (try
      (try-try-again
        {:sleep 30000 :tries 10}
        #(let [result (client/get url {:headers {"User-Agent" user-agent} :query-params query-params})]

          (log/info "Fetched" url query-params)

          (condp = (:status result)
            200 (parse-page url (:site_url site-info) (:body result))
            404 {:url url :actions [] :extra {:after nil :before nil :error "Result not found"}}
            
            (do
              (log/error "Unexpected status code" (:status result) "from" url)
              (log/error "Body of error response:" (:body url))
              (throw (new Exception "Unexpected status"))))))

      (catch Exception ex (do
        (log/error "Error fetching" url)
        (log/error "Exception:" ex)
        {:url url :actions [] :extra {:error "Failed to retrieve page"}})))))


; https://api.stackexchange.com/docs/throttle
; 10,000 per day is about 1 every 8 seconds. 6/minute = 1 every 10 seconds
(def fetch-page-throttled (throttle-fn fetch-page 6 :minute))

(defn fetch-pages
  "Lazy sequence of pages for the domain."
  ([site-info domain]
    ; Pagination starts with 1! 
    ; https://api.stackexchange.com/docs/paging
    (fetch-pages site-info domain 1))

  ([site-info domain page-number]
    (log/info "Query page" page-number "of" site-info domain)
    (let [result (fetch-page-throttled site-info domain page-number)
          end (-> result :extra :has-more not)
          near-quota (when (:quota_max result)
                       (< (/ (:quota_remaining result 0) (:quota_max result)) 0.1))
          backoff-seconds (-> result :backoff)]

      ; In last 10% of quota, sleep more between requests.
      (when near-quota
        (log/info "Warning! " (:quota_remaining result) "/" (:quota_max result) "quota remaining!")
        (Thread/sleep 20000))

      (when backoff-seconds
        (Thread/sleep (* 1000 backoff-seconds)))

      (if end
        [result]
        (lazy-seq (cons result (fetch-pages site-info domain (inc page-number))))))))

(defn any-action-dates-after?
  [site-info date page]
  (let [dates (map #(-> % :occurred-at coerce/from-string) (:actions page))]
    (some #(clj-time/after? % date) dates)))

(defn fetch-parsed-pages-after
  "Fetch seq parsed pages of Actions until all actions on the page happened before the given time."
  [site-info domain date]
  (let [pages (fetch-pages site-info domain)]
    (take-while (partial any-action-dates-after? site-info date) pages)))

(defn check-all-sites
  "Check all sites for unseen links."
  [artifacts bundle-chan]
  (log/info "Start crawl all Sites on stackexchange at" (str (clj-time/now)))
  (status/add! "stackexchange-agent" "process" "scan-sites" 1)
  (let [[domain-list-url domain-list] (get artifacts "domain-list")
        [site-list-url site-list] (get artifacts "stackexchange-sites")
        
        ; Sites artifact is {:site_url :api_site_parameter}
        sites (json/read-str site-list :key-fn keyword)
        num-sites (count sites)
        site-counter (atom 0)
        cutoff-date (-> 10 clj-time/days clj-time/ago)]
    
    (doseq [site-info sites]
      (swap! site-counter inc)
        (log/info "Query site" (:site_url site-info) @site-counter "/" num-sites " total progress " (int (* 100 (/ @site-counter num-sites) num-sites)) "%")
        (let [pages (fetch-parsed-pages-after site-info "doi.org" cutoff-date)]
          
          ; Each page is big, so send them one at once.
          (doseq [page pages]
            (let [package {:source-token source-token
                       :source-id "stackexchange"
                       :license license
                       :agent {:version version :artifacts {:stackexchange-site-version site-list-url}}
                       :extra {:cutoff-date (str cutoff-date) :queried-domain "doi.org"}
                       :pages [page]}]
          (log/info "Sending package...")
          ; (>!! bundle-chan package)
          (clojure.pprint/pprint package)
          )))))
  (log/info "Finished scan."))

(def agent-definition
  {:agent-name "stackexchange-agent"
   :version version
   :schedule [{:name "check-all-sites"
              :seconds 432000 ; wait five days between scans!
              :fixed-delay true
              :fun check-all-sites
              :required-artifacts ["stackexchange-sites"]}]
   :runners []})

(defn -main [& args]
  (c/run agent-definition))
