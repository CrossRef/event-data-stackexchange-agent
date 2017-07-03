(defproject event-data-stackexchange-agent "0.2.11"
  :description "Crossref Event Data stackexchange.com Agent"
  :url "http://eventdata.crossref.org"
  :license {:name "The MIT License (MIT)"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.crossref.event-data-agent-framework "0.2.0"]
                 [event-data-common "0.1.30"]
                 [throttler "1.0.0"]
                 [commons-codec/commons-codec "1.10"]
                 [robert/bruce "0.8.0"]
                 [clj-http "2.3.0"]
                 [org.apache.commons/commons-lang3 "3.5"]]
  :main ^:skip-aot event-data-stackexchange-agent.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}
             :prod {:resource-paths ["config/prod"]}
             :dev  {:resource-paths ["config/dev"]}})
