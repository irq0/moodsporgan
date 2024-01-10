(ns moodsporgan.core
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def cli-options
  [[nil "--auth" "Authenticate spotify"]
   [nil "--midi-device" "Midi device. Env: MOODSPORGAN_MIDI_DEVICE"
    :default (or (System/getenv "MOODSPORGAN_MIDI_DEVICE") "nanoKONTROL2, USB MIDI, nanoKONTROL2")]
   [nil "--spotify-client-id" "Spotify client id. Env SPOTIFY_CLIENT_ID"
    :default (System/getenv "SPOTIFY_CLIENT_ID")]
   [nil "--spotify-client-auth" "Spotify client auth. Env: SPOTIFY_CLIENT_AUTH"
    :default (System/getenv "SPOTIFY_CLIENT_AUTH")]
   [nil "--state-dir" "State dir. Env MOODSPORGAN_STATE"
    :default (or (System/getenv "MOODSPORGAN_STATE") "/var/tmp")]
   ["-h" "--help"]])

(def +playlists+ {:favorites "spotify:playlist:0iQBZgt8O7LjjuAHnaKOXy"
                  :recommendations "spotify:playlist:5h9qqEP0LJjiqfOXj3G9wD"
                  :solo-1 "spotify:playlist:16Hx9uoQJ8Vlz7T0K9xAsc"})

(def +config+ (atom {:state-dir nil
                     :client-id nil
                     :midi-device nil
                     :client-auth nil}))

(def +fallback-player-id+ "fc0b5d776a6c9ba47e2f3b98011f70a3ffca4b18")

(defn playlist-id [uri]
  (->> uri
       (re-find #"spotify:playlist:(\w+)")
       second))

(defn persist-tokens [tokens]
  (spit (io/as-file (str (:state-dir @+config+) "/moodsporgan-tokens.edn"))
        (prn-str tokens)))

(defn load-tokens []
  (try
    (let [data (edn/read (java.io.PushbackReader. (io/reader (str (:state-dir @+config+) "/moodsporgan-tokens.edn"))))]
      (if (map? data)
        data
        {}))
    (catch java.lang.Exception _
      {})))

(def +spotify-api+ (atom nil))

;; SPOTIFY

(defn spotify-auth-api-client [client-id client-auth]
  (-> (se.michaelthelin.spotify.SpotifyApi/builder)
      (.setClientId client-id)
      (.setClientSecret client-auth)
      (.setRedirectUri (se.michaelthelin.spotify.SpotifyHttpManager/makeUri "https://files.irq0.org/cgi-bin/spotify.sh"))
      (.build)))

(defn spotify-auth-refresh [client-id client-auth access-token refresh-token]
  (-> (se.michaelthelin.spotify.SpotifyApi/builder)
      (.setClientId client-id)
      (.setClientSecret client-auth)
      (.setAccessToken access-token)
      (.setRefreshToken refresh-token)
      (.build)))

(defn spotify-auth-api-token [access-token refresh-token]
  (-> (se.michaelthelin.spotify.SpotifyApi/builder)
      (.setAccessToken access-token)
      (.setRefreshToken refresh-token)
      (.build)))

(defn spotify-auth-get-code-url [api]
  (-> (.authorizationCodeUri api)
      (.scope "user-read-playback-state user-modify-playback-state user-read-currently-playing user-library-modify playlist-modify-public playlist-modify-private")
      (.build) (.execute)))

(defn spotify-auth-process-redirect-uri [api uri]
  (let [code (second (re-find #"code=(.+)" uri))]
    (-> (.authorizationCode api code) (.build) (.execute))))

(defn spotify-auth-renew! [tokens]
  (let [api (spotify-auth-refresh (:client-id @+config+) (:client-auth @+config+) (:access tokens) (:refresh tokens))
        refresh (-> (.authorizationCodeRefresh api) (.build) (.execute))]
    (log/info api refresh)
    (.setAccessToken api (.getAccessToken refresh))
    (persist-tokens {:refresh (:refresh tokens) :access (.getAccessToken refresh)})
    (reset! +spotify-api+ api)))

(defn spoti [builder]
  (try
    (-> builder (.build) (.execute))
    (catch se.michaelthelin.spotify.exceptions.detailed.UnauthorizedException e
      (log/error e)
      (when (= (.getMessage e) "The access token expired")
        (spotify-auth-renew! (load-tokens))))))

;; TODO(irq0) add error handling and reauth
(defn spotify-auth! []
  (let [tokens (load-tokens)
        api (spotify-auth-api-token (:access tokens) (:refresh tokens))]
    (try
      (log/info (spoti (.getCurrentUsersProfile api)))
      (reset! +spotify-api+ api)
      (catch se.michaelthelin.spotify.exceptions.detailed.UnauthorizedException e
        (log/error (.getMessage e))
        (when (= (.getMessage e) "Invalid access token")
          (System/exit 1))
        (when (= (.getMessage e) "The access token expired")
          (spotify-auth-renew! tokens))))))

(defn interactive-new-auth! []
  (let [api (spotify-auth-api-client (:client-id @+config+) (:client-auth @+config+))
        auth-url (spotify-auth-get-code-url api)
        redirect-url (do (println "Visit: " (str auth-url))
                         (println "Enter redirect URL: ")
                         (flush)
                         (read-line))
        creds (spotify-auth-process-redirect-uri api redirect-url)]
    (reset! +spotify-api+ (spotify-auth-api-token (.getAccessToken creds) (.getRefreshToken creds)))
    (log/info "Tokens: " {:refresh (.getRefreshToken creds) :access (.getAccessToken creds)})
    (persist-tokens {:refresh (.getRefreshToken creds) :access (.getAccessToken creds)})))

(defn spotify-playlist-track-uris [id]
  (->> (spoti (.getPlaylistsItems @+spotify-api+ id))
       (.getItems)
       (map #(-> (.getTrack %) (.getId)))))

;; MIDI

(defn scale [value [src-min src-max] [target-min target-max]]
  (let [src-diff (- src-max src-min)
        target-diff (- target-max target-min)]
    (+
     (*
      (/ (- value src-min)
         src-diff)
      target-diff)
     target-min)))

(defn resolve-event [event]
  {:switch
   (case (:data1 event)
     0 :fader-1
     1 :fader-2
     2 :fader-3
     3 :fader-4
     4 :fader-5
     5 :fader-6
     6 :fader-7
     7 :fader-8
     16 :knob-1
     17 :knob-2
     18 :knob-3
     19 :knob-4
     20 :knob-5
     21 :knob-6
     22 :knob-7
     23 :knob-8
     58 :track-left
     59 :track-right
     46 :cycle
     60 :marker-set
     61 :marker-left
     62 :marker-right
     43 :rewind
     44 :fast-forward
     42 :stop
     41 :play
     45 :record
     32 :solo-1
     33 :solo-2
     34 :solo-3
     35 :solo-4
     36 :solo-5
     37 :solo-6
     38 :solo-7
     39 :solo-8
     48 :mute-1
     49 :mute-2
     50 :mute-3
     51 :mute-4
     52 :mute-5
     53 :mute-6
     54 :mute-7
     55 :mute-8
     64 :rec-1
     65 :rec-2
     66 :rec-3
     67 :rec-4
     68 :rec-5
     69 :rec-6
     70 :rec-7
     71 :rec-8
     :unknown)
   :type (cond
           (<= 0 (:data1 event) 23)
           :variable
           :else
           :button)
   :value (cond
            (<= 0 (:data1 event) 23)
            (:data2 event)

            (= (:data2 event) 0)
            :released

            (= (:data2 event) 127)
            :pressed

            :else
            :unknown)})

(defn -scale-midi-to-pct [val]
  (scale val [0 127] [0 100]))

(defn -scale-midi-to-hue-bri [val]
  (int (scale val [0 127] [0 255])))

(defn -scale-midi-to-hue-ct [val]
  (int (scale val [0 127] [153 454])))

(defn -scale-midi-to-0-1 [val]
  (float (scale val [0 127] [0.0 1.0])))

(def fallback-button-states
  {:cycle :released,
   :fader-1 0,
   :fader-2 0,
   :fader-3 0,
   :fader-4 0,
   :fader-5 0,
   :fader-6 0,
   :fader-7 0,
   :fader-8 0,
   :fast-forward :released,
   :knob-1 0,
   :knob-2 0,
   :knob-3 0,
   :knob-4 0,
   :knob-5 0,
   :knob-6 0,
   :knob-7 0,
   :knob-8 0,
   :marker-right :released,
   :mute-1 :released,
   :mute-2 :released,
   :mute-3 :released,
   :mute-4 :released,
   :mute-5 :released,
   :mute-6 :released,
   :mute-8 :released,
   :play :released,
   :rec-1 :released,
   :rec-2 :released,
   :rec-3 :released,
   :rec-4 :released,
   :rec-5 :released,
   :rec-6 :released,
   :rec-8 :released,
   :solo-1 :released,
   :solo-4 :released,
   :solo-8 :released,
   :stop :released,
   :track-left :released,
   :track-right :released})

(defn persist-button-states [_key _ref _old new]
  (spit (io/as-file (str (:state-dir @+config+) "/moodsporgan-button-states.edn"))
        (prn-str new)))

(defn load-button-states []
  (try
    (let [data (edn/read (java.io.PushbackReader. (io/reader (str (:state-dir @+config+) "/moodsporgan-button-states.edn"))))]
      (log/info "Persisted button states:" data)
      (if (map? data) data fallback-button-states))
    (catch java.lang.Exception _
      fallback-button-states)))

(defonce button-states (atom {}))

(def +buttons-to-spotify-track-attributes+
  {;; 1.0 highly likely acoustic
   :acousticness {:button :fader-3
                  :api-fn (memfn target_acousticness val)
                  :val-fn -scale-midi-to-0-1}
   ;; has vocals? ooh ahh = instrumental. instrumental > 0.5
   :instrumentalness {:button :fader-4
                      :api-fn (memfn target_instrumentalness val)
                      :val-fn -scale-midi-to-0-1}
   ;; 0 least, 1 most danceable
   :danceability {:button :knob-2
                  :api-fn (memfn target_danceability val)
                  :val-fn -scale-midi-to-0-1}
   ;; intensity, activity (fast loud noisty) death metal: high, bach prelude low
   :energy {:button :fader-5
            :api-fn (memfn target_energy val)
            :val-fn -scale-midi-to-0-1}
   ;; positive cheerful?
   :valence {:button :fader-6
             :api-fn (memfn target_valence val)
             :val-fn -scale-midi-to-0-1}
   ;; performed life? 0.8 strong
   :liveness {:button :knob-3
              :api-fn (memfn target_liveness val)
              :val-fn -scale-midi-to-0-1}
   ;; spoken word? 1.0 likely audiobook
   :speechiness {:button :knob-4
                 :api-fn (memfn target_speechiness val)
                 :val-fn -scale-midi-to-0-1}
   ;; bpm
   :tempo {:button :knob-5
           :api-fn (memfn target_tempo val)
           :val-fn #(float (scale % [0 127] [0 200]))}

   :duration-ms {:button :fader-7
                 :api-fn (memfn target_duration_ms val)
                 :val-fn #(int (scale % [0 127] [0 1200000]))}
   ;; 0 - 100%
   :popularity {:button :fader-2
                :api-fn (memfn target_popularity val)
                :val-fn #(int (scale % [0 127] [0 100]))}})

(def +button-to-genre+
  {:solo-2 :indie
   :mute-2 :ambient
   :rec-2 :rock
   :solo-3 :punk
   :mute-3 :trip-hop
   :rec-3 :jazz
   :solo-4 :drum-and-bass
   :mute-4 :grunge
   :rec-4 :party
   :solo-5 :industrial
   :mute-5 :alt-rock
   :rec-5 :chill
   :solo-6 :german
   :mute-6 :happy
   :rec-6 :sad
   :solo-7 :reggae
   :mute-7 :goth
   :rec-7 :study})

(defn make-recommendation-request [genres tracks attributes]
  (let [builder
        (doto (.getRecommendations @+spotify-api+)
          (.seed_genres (string/join ","  genres))
          (.seed_tracks (string/join "," tracks))
          (.limit (int 42)))]
    (doseq [[attrib value] attributes
            :let [method (get-in +buttons-to-spotify-track-attributes+ [attrib :api-fn])]
            :when (> value 0)]
      (method builder value))
    builder))

(defn spoti-play [builder]
  (try
    (spoti builder)
    (catch se.michaelthelin.spotify.exceptions.detailed.NotFoundException _
      (log/info "No active playback. Trying fallback player")
      (spoti (.device_id builder +fallback-player-id+)))))

(defn spotify-recommendation [states]
  (let [genres (->> states
                    (filter #(re-find #"mute|rec|solo" (name (key %))))
                    (filter (fn [[_ s]] (= s :pressed)))
                    (map (fn [[btn _]] (get +button-to-genre+ btn)))
                    (remove nil?)
                    (map name))

        tracks (->> (concat []
                            (when (= (get states :rec-8) :pressed)
                              (spotify-playlist-track-uris (-> +playlists+ :favorites playlist-id)))
                            (when (= (get states :mute-8) :pressed)
                              (spotify-playlist-track-uris (-> +playlists+ :recommendations playlist-id))))
                    shuffle
                    (take (max 0 (- 5 (count genres)))))

        attributes (into {}
                         (map (fn [[attrib {:keys [button val-fn]}]]
                                (let [attrib-value (get states button)]
                                  [attrib (val-fn attrib-value)]))
                              +buttons-to-spotify-track-attributes+))]
    (log/info "Seeds (tracks genres attribs)" tracks genres attributes)
    (when (not (and (empty? genres) (empty? attributes)))
      (let [request (make-recommendation-request genres tracks attributes)
            recommendations (spoti request)
            uris (->> recommendations .getTracks (map #(.getUri %)))
            uris-json (.toJsonTree (com.google.gson.Gson.) uris)]
        (log/info (->> recommendations .getTracks (map #(.getName %)) (string/join ", ")))
        (log/debug uris)

        (spoti-play (-> (.startResumeUsersPlayback @+spotify-api+)
                        (.uris uris-json)))))))

(defn handle-midi-event [midi-event]
  (let [{:keys [switch type value] :as event} (resolve-event midi-event)]
    (log/debug midi-event event)
    (swap! button-states assoc switch value)
    (when (and (= type :button) (= value :pressed))
      (case switch
        :stop (spoti (.pauseUsersPlayback @+spotify-api+))
        :play (spoti-play (.startResumeUsersPlayback @+spotify-api+))
        :track-right (spoti (.skipUsersPlaybackToNextTrack @+spotify-api+))
        :track-left (spoti (.skipUsersPlaybackToPreviousTrack @+spotify-api+))
        :cycle (spotify-recommendation @button-states)
        :rec-1  (spoti-play (-> (.startResumeUsersPlayback @+spotify-api+) (.context_uri (:favorites +playlists+))))
        :mute-1 (spoti-play (-> (.startResumeUsersPlayback @+spotify-api+) (.context_uri (:recommendations +playlists+))))
        :solo-1 (spoti-play (-> (.startResumeUsersPlayback @+spotify-api+) (.context_uri (:solo-1 +playlists+))))
        nil))))

(defn make-midi-receiver []
  (reify javax.sound.midi.Receiver
    (^void send [_this ^javax.sound.midi.MidiMessage msg ^long _timestamp]
      (try
        (when (instance? javax.sound.midi.ShortMessage msg)
          (let [sm (cast javax.sound.midi.ShortMessage msg)
                chan (.getChannel sm)
                d1 (.getData1 sm)
                d2 (.getData2 sm)
                cmd (.getCommand sm)]
            (handle-midi-event {:channel chan
                                :data1 d1
                                :data2 d2
                                :command cmd})))
        (catch Throwable th
          (log/error th "midi recv / decode failed" @button-states)))
      nil)
    (^void close [_this]
      (log/info "closed")
      (System/exit 23))))

(def +nano-kontrol-in+ (atom nil))

(defn midi-devices []
  (map bean (javax.sound.midi.MidiSystem/getMidiDeviceInfo)))

(defn get-midi-device [desc]
  (let [dev (some #(when (and (= (.getDescription %) desc)
                              (= (type %)
                                 com.sun.media.sound.MidiInDeviceProvider$MidiInDeviceInfo))
                     %)
                  (javax.sound.midi.MidiSystem/getMidiDeviceInfo))]
    (log/info "Found device: " dev)
    (when (nil? dev)
      (log/error "No midi device" desc "Exiting")
      (System/exit 5))
    (javax.sound.midi.MidiSystem/getMidiDevice dev)))

(defn start-midi-receiver! [dev-desc]
  (when @+nano-kontrol-in+
    (when (.isOpen @+nano-kontrol-in+)
      (.close @+nano-kontrol-in+)))
  (reset! +nano-kontrol-in+ (get-midi-device dev-desc))
  (-> @+nano-kontrol-in+ .getTransmitter (.setReceiver (make-midi-receiver)))
  (.open @+nano-kontrol-in+))

(defn -main [& args]
  (java.util.Locale/setDefault java.util.Locale/ENGLISH)
  (let [{:keys [options summary errors]} (parse-opts args cli-options)]
    (when (or errors (:help options))
      (log/info "Usage:")
      (doseq [line (string/split summary #"\n")]
        (log/info line))
      (when errors
        (log/error errors))
      (System/exit 1))
    (when (:auth options)
      (interactive-new-auth!)
      (System/exit 0))
    (reset! +config+ {:state-dir (:state-dir options)
                      :midi-device (:midi-device options)
                      :client-id (:spotify-client-id options)
                      :client-auth (:spotify-client-auth options)}))

  (log/info "Midi devices:" (midi-devices))
  (start-midi-receiver! (:midi-device @+config+))
  (spotify-auth!)
  (reset! button-states (load-button-states))
  (add-watch button-states :persist persist-button-states)
  (log/info "🖖"))
