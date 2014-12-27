(defproject touch-magic "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [http-kit "2.1.16"]
                 [cheshire "5.4.0"]
                 [compojure "1.1.6"]
                 [ring/ring "1.2.1"]]
  :main ^{:skip-aot true} touch-magic.server)
