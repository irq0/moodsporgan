(defproject moodsporgan "0.1.0-SNAPSHOT"
  :description "Spotify Mood Organ"
  :url "https://github.com/irq0/moodsporgan"
  :license {:name "GPL-3.0-or-later"
            :url "https://www.gnu.org/licenses/gpl-3.0.en.html"}
  :jvm-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"
             "-Xmx128m"]
  :aot :all
  :exclusions [org.slf4j/slf4j-nop]
  :main moodsporgan.core
  :profiles {:uberjar {:omit-source true
                       :aot :all}}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.slf4j/log4j-over-slf4j "2.0.10"]
                 [org.slf4j/jul-to-slf4j "2.0.10"]
                 [org.slf4j/jcl-over-slf4j "2.0.10"]
                 [org.slf4j/slf4j-api "2.0.10"]
                 [org.slf4j/osgi-over-slf4j "2.0.10"]
                 [ch.qos.logback/logback-classic "1.4.14"]
                 [org.clojure/tools.cli "1.0.219"]
                 [org.clojure/tools.logging "1.2.4"]
                 [se.michaelthelin.spotify/spotify-web-api-java "8.3.4"]]
  :repl-options {:init-ns moodsporgan.core})
