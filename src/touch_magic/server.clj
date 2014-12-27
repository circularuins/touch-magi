(ns touch-magic.server
  (:require  [org.httpkit.server :refer 
              [with-channel websocket? on-receive send! on-close close run-server]]
             [org.httpkit.timer :refer [schedule-task]]
             [cheshire.core :refer :all]
             [compojure.route :refer [not-found resources]]
             [compojure.handler :refer [site]]
             [compojure.core :refer [defroutes GET POST DELETE ANY context]]))

;; 接続したチャネルを記録するマップ
(def game-channel-hub (atom {}))
;; ユーザー毎のチャネルと送信データを保存するマップ
(def player1-data (atom {}))
(def player2-data (atom {}))
;; 初回のデータ交換が済んでいるかどうか判別するフラグ
(def complete-data-change (atom false))
;; mapが空ではないことを判別するユーザー定義関数
(def not-empty? (complement empty?))

(defn send-data
  [channel data]
  (send!
   channel
   {:status 200
    :headers {"Content-Type" "application/json; charset=utf-8"}
    :body (generate-string {:data data :time (new java.util.Date)})}
   ))

(defn send-exchange
  []
  (let [channel-1 (first (keys @player1-data))
        data-1 (first (vals @player1-data))
        channel-2 (first (keys @player2-data))
        data-2 (first (vals @player2-data))]
    (send-data channel-1 data-2)
    (send-data channel-2 data-1)
    (reset! player1-data {})
    (reset! player2-data {}))
  )

(defn game-handler
  "二人のクライアントが接続してデータを交換する"
  [req]
  (with-channel req channel
    (swap! game-channel-hub assoc channel req) ;ハブにチャネルを追加
    ;; 接続解除時の処理
    (on-close channel (fn [status]
                        (println "チャネル閉鎖")
                        (swap! game-channel-hub dissoc channel) ;ハブからチャネルを削除
                        (reset! complete-data-change true)
                        ))
    ;; 接続開始時の処理
    (if (websocket? channel)
      (do (println "WebSocketチャネル生成")
          (send! channel "WebSocketチャネル生成"))
      (println "HTTPチャネル生成"))
    ;; データ受信時の処理
    (on-receive
     channel
     (fn [data]
       (if @complete-data-change
         ;; データ交換済の場合
         (cond
          (= channel (first (keys @game-channel-hub))) (send-data (second (keys @game-channel-hub)) data)
          (= channel (second (keys @game-channel-hub))) (send-data (first (keys @game-channel-hub)) data))
         ;; データ交換済でない場合
         (cond
          ;; データが空の時
          (and (empty? @player1-data) (empty? @player2-data))
          ;; データ1に書き込む
          (swap! player1-data assoc channel data)
          ;; データ1のみ埋まっている時
          (and (not-empty? @player1-data) (empty? @player2-data))
          (if (= (first (keys @player1-data)) channel)
            ;; 同一チャネルのデータが来たらデータ1を上書き
            (do
              (reset! player1-data {})
              (swap! player1-data assoc channel data))
            ;; 別チャネルのデータが来たらデータ2に書き込む
            (swap! player2-data assoc channel data))))

       ;; データ1とデータ2が揃った時、
       ;; データ1をプレイヤー2に、データ2をプレイヤー1に配信する
       (if (and (not-empty? @player1-data) (not-empty? @player2-data))
         (do
           (send-exchange)
           ;; 交換済フラグをたてる
           (reset! complete-data-change true)))))))

(defn app 
  "単純なレスポンスを返します" 
  [req]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    "hello HTTP!"})

(defn ws-handler 
  "WebSocketチャネルでの通信を行います"
  [req]
  (with-channel req channel ;;チャネルを取得する
    (on-close channel (fn [status]
                        (println "channel closed"))) ;; セッションが終了した場合
    (if (websocket? channel) ;; セッションが開始された場合でそれがWebSocketの場合
      (do (println "WebSocketのチャネルが生成されました")
      (send! channel "WebSocketのチャネルが生成されました"))
      (println "HTTPチャネルが生成されました") ;; WebSocketではない場合
      )
    (on-receive 
     channel ;; データを受信した場合
     (fn [data]  ; dataはクライアントから送られたデータである。
       (println data)
       ;; send!は(send! channel data close-after-send?)で３つめの引数のclose-after-send?
       ;; はsend!の後にクローズするかしないかのオプションである。
       ;; もし、３つめのオプションを指定しなければHTTPのチャネルではtrueであり、WebSocketで
       ;; はfalseがデフォルトとなる。
       (send! channel (str "一発目のデータを非同期で送ります " data))
       (send! channel (str "二発目のデータを非同期で送ります " data)))))) 

(defn streaming-handler [request]
  "WebSocketのストリーム通信を行います。あるインターバルで指定された回数、サーバーからpushします"
  (with-channel request channel
    (let [count 10 interval 2000]
      (on-close channel (fn [status] (println "チャネルがクローズされました, " status)))
      (loop [id 0]
        (when (< id count) ;; 10回クライアントに送ります。
          (schedule-task 
           (* id interval) ;; 200msごとに通信する。
           (send! channel (str "message from server #" id) false)) ; falseはsend!の後にクローズしない
          (recur (inc id))))
      (schedule-task (+ (* count interval) 1000) (close channel))))) ;; 10秒経ったらクローズします。


(def channel-hub (atom {}))

(defn long-poll-handler 
  "long pollのサンプルです"
  [request]
  (with-channel request channel
    ;; チャネルになにかを保持してイベント発生時にそれをクライアントに送ります。
    ;; atomであるchanell-bubを監視してイベント発生時にクライアントにおくります。
    (swap! channel-hub assoc channel request) 
    (on-close channel (fn [status]
                        ;; チャネルをクローズします。
                        (swap! channel-hub dissoc channel)))))

(defn someevent 
  "イベントのサンプルです。チャネルにイベントを書き込みます。この関数を随時実行するとクライアントにその内容が送られます"
  [data]
  (doseq [channel (keys @channel-hub)]
    (send! 
     channel {:status 200                                                  
              :headers {"Content-Type" "application/json; charset=utf-8"}
              :body data})))

(defonce server (atom nil)) ;; 競合を避けるためatomを使う。

(defn stop-server 
  "msの指定時間を待ってサーバーをgracefulに停止させます。タイムアウトのオプションがなければ即時に停止させます。"
  []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))


(defn show-landing-page "単純なレスポンスを返します"
  [req]   
  "OK!")

(defroutes all-routes
  (GET "/" [] show-landing-page)
  (GET "/ws" [] ws-handler)     ;; WebSocket通信を行います。
  (GET "/stream" [] streaming-handler) ;; WebSocketのストリーム通信を行います。
  (GET "/poll" [] long-poll-handler) ;; WebSocketのlong pollの通信を行います。
  (GET "/game" [] game-handler) ;; データ交換を行う通信。
  (GET "/user/:id" [id]
       (str "<h1>Hello user " id "</h1>"))  
  (resources "/")  ;; resources/public以下のhtmlファイルが表示されます。
  (not-found "<p>Page not found.</p>"));; さもなければエラーを返します。

(defn -main 
  "lein runで呼ばれるメインプログラムです"
  [& args]
  (println "starting server ...")
  (reset! server (run-server (site #'all-routes) {:port 3003})))

(comment 
  ;; それぞれのサービスを起動、停止します。
  ;; wscat -c urlでクライアントのテストができます。
  ;; (例) wscat -c ws://localhost:3003

  (reset! server (run-server #'app {:port 3003}))
  ;;wscat -c ws://localhost:3003
  (stop-server)

  (reset! server (run-server #'ws-handler {:port 3003}))
  ;;wscat -c ws://localhost:3003
  (stop-server)

  (reset! server (run-server #'streaming-handler {:port 3003}))
  ;;wscat -c ws://localhost:3003
  (stop-server)

  (reset! server (run-server #'long-poll-handler {:port 3003}))
  ;;wscat -c ws://localhost:3003

(someevent "test")
(someevent "おーい、元気か〜")

  (stop-server)

  (reset! run-server (site #'all-routes) {:port 3003})
  ;;wscat -c ws://localhost:3003/ws
  ;;wscat -c ws://localhost:3003/stream
  ;;wscat -c ws://localhost:3003/poll

  (stop-server))
