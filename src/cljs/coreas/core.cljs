(ns coreas.core
  (:require [reagent.core :as reagent :refer [atom cursor]]
            [reagent.session :as session]
            [secretary.core :as secretary :include-macros true]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [cljsjs.react :as react]
            [cljs.core.async :as ca :refer [chan put! timeout]]
            [ajax.core :refer [GET]]
            [cljs.core.match :refer-macros [match]]
            [clojure.core.reducers :refer [reduce]]
            [coreas.label-data :as labels])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop alt!]])
  (:import goog.History))

(enable-console-print!)
(def NUM_RESOURCES 9)
(def ICON_SIZE 15)

;; -------------------------
;; Views
(defn listen [el type]
  (let [out (chan)]
    (events/listen el type
                   (fn [e] (put! out e)))
    out))

(defn img-wrapper [img]
  (reagent/create-class
   {:component-did-mount #(-> % (.getDOMNode) (.appendChild img))
    :reagent-render (fn [img] [:span])}))

(defn canvas-comp [attrs & props]
  (reagent/create-class

   {:paint (or (:paint attrs) (fn [this] (print "default paint")))
    :component-did-mount (fn [this]
                           (let [c (.getDOMNode this)]
                             (aset c "width" (:width attrs))
                             (aset c "height" (:height attrs))
                             (let [ctx (.getContext c "2d")]
                               (aset c "d" ctx)
                               ;; We're doing this .call business
                               ;; instead of a straight (.paint this
                               ;; ...) to stop google closure from
                               ;; optimizing the name of .paint away
                               (.call (aget this "paint") this ctx props))
                             ))
    :component-will-receive-props
    (fn [this [_ _ & props]]
      ;; We're doing this .call business instead of a straight (.paint this
      ;; ...) to stop google closure from optimizing the name of
      ;; .paint away
      (.call (aget this "paint") this (-> this (.getDOMNode) (aget "d")) props))
    ;;    :component-will-update (fn [this] (pr "will-update"))
    :reagent-render (fn [attrs & props]
                      [:canvas (dissoc attrs :width :height :paint)])}))

(defn canvas->img [c]
  (let [img (js/Image.)]
    (set! (.-src img) (.toDataURL c))
    img))

(defn img-data->rgba [imd]
  (let [width (.-width imd)
        height (.-height imd)
        data (.-data imd)]
    (for [x (range width)]
      (for [y (range height)]
        (let [base (* 4 (+ x (* width y)))]
          {:r (aget data base) :g (aget data (inc base))
           :b (aget data (+ 2 base)) :a (aget data (+ 3 base))})))))

(defn aget-in [x ats]
  (apply aget `(~x ~@ats)))

(defn get-pix [imdat x y]
  (let [w (.-width imdat)
        base (* 4 (+ x (* w y)))
        data (.-data imdat)]
    {:r (aget data base) :g (aget data (inc base))
     :b (aget data (+ 2 base)) :a (aget data (+ 3 base))}))

(defn img-bundle-future
  "Returns a channel on which a record is written containing {:img
  img :data data} where img is the image itself and data is the image
  data object."
  [url]
  (let [im (js/Image.)]
    (aset im "src" url)
    (let [ch (chan)]
      (aset im "onload"
            (fn [e]
#_              (print "loaded " url)
              (let [c (.createElement js/document "canvas")
                    d (.getContext c "2d")
                    w (.-width im)
                    h (.-height im)]
                (set! (.-width c) w)
                (set! (.-height c) h)
                (-> d (.drawImage im 0 0))
                (go (>! ch {:img im
                            :canvas c
                            :ctx d
                            :data (.getImageData d 0 0 w h)}))
                )))
      ch)))



(defn relpos [e]
  (let [x (- (.-pageX e) (-> e (.-target) (.-offsetLeft)))
        y (- (.-pageY e) (-> e (.-target) (.-offsetTop)))]
    {:x x :y y}))


(defn dispatch [msg])

(defn forever [c v]  (go-loop []  (>! c v) (recur)))

(defn json-future [url]
  (let [ch (chan)]
    (go
      (GET url {:handler #(forever ch %)
                :error-handler #(print "an error occurred: " %)
                :response-format :json
                :keywords? true}))

    ch))

(defn raw-json-future [url]
  (let [ch (chan)]
    (go
      (GET url {:handler #(forever ch %)
                :error-handler #(print "an error occurred: " %)
                :response-format :json}))

    ch))

(defn img-future [url]
  (let [img (js/Image.)
        ch (chan)]
    (set! (.-src img) url)
    (set! (.-onload img) (fn [] #_ (print "loaded " url) (forever ch img)))
    ch))

(defn ch->atom [ch]
  (let [atm (atom nil)]
    (go (reset! atm (<! ch)))
    atm))

(defn color->text [c] (str (:r c) "/" (:g c) "/" (:b c)))

(defn make-map
  "like _.object, convert a list of [key, value] pairs into a map."
  [pairs]
  (zipmap (map first pairs) (map second pairs)))

(defn res [kwd] (session/get-in [:res kwd]))

(defn ri [n] (int (* n (.random js/Math))))
(defn rcoin [p] (< (.random js/Math) p))

(defn draw-resource-icon [d x y which sign]
  (.drawImage d (:img (res :icons-img)) (* ICON_SIZE sign) (* ICON_SIZE which) ICON_SIZE ICON_SIZE x y ICON_SIZE ICON_SIZE))

(defn add-resource [resources {:keys [which sign]}]
  (update resources which (if (= sign 0) inc dec)))

(defn add-resources! [rs]
  (let [cur (cursor session/state [:game-state :resources])
        old-resources @cur
        new-resources (reduce add-resource old-resources rs)
        new-resources-ok (every? #(>= % 0) new-resources)]
    (if new-resources-ok
      (reset! cur new-resources))
    new-resources-ok))

(defn resources-of-country-ix [n]
  (((session/get :game-state) :country-resources) n))

(defn ixfy [seq]
  (for [n (range (count seq))]
    [(get seq n) n]))

(defn accessible-from?
  "Is country `n` accessible in state `game-state`?"
  [game-state n]
  (some #((:countries game-state) %) ((res :adjacencies) n)))

(defn paint-fn [w h]
  (fn [this d [game-state]]
    (let [cc (:cc game-state)
          info (res :map-pieces-info)]

      ;; Draw countries
      (doseq [[color n] (ixfy (:colors info))]
        (let [extent (get-in info [:extents (keyword color)])
              size (get-in info [:sizes (keyword color)])
              basex (* (:x (:cell_size info)) (mod n (:num_cells info)))
              basey (* (:y (:cell_size info)) (int (/ n (:num_cells info))))
              sizex (:x size)
              sizey (:y size)]
          (doto (:ctx (res :map-pieces-img))
            (aset "globalCompositeOperation" "source-atop")
            (aset "fillStyle" (cond
                                (contains? (:countries game-state) n) "#a65"
                                (accessible-from? game-state n) (if (= n cc) "#aff" "#cc7")
                                true "#777"))
            (.fillRect basex basey sizex sizey))

          (doto d
            (.drawImage (:canvas (res :map-pieces-img))
                        basex basey sizex sizey
                        (:min_x extent) (:min_y extent) sizex sizey))))

      ;; Draw oceans and country borders
      (doto d (.drawImage (res :outline-img) 0 0))

      ;; Draw resource icons
      (doseq [[color n] (ixfy (:colors info))]
        (let [[x y] ((res :centers) color)]
          (when (not (contains? (:countries game-state) n))
            (let [resources (resources-of-country-ix n)
                  xoffset (int (/ (* (inc ICON_SIZE) (count resources)) 2))
                  yoffset (int (/ ICON_SIZE 2))]
              (doseq [[resource i] (ixfy resources)]
                (draw-resource-icon d (+ (* (inc ICON_SIZE) i) (- x xoffset)) (- y yoffset)
                                    (:which resource) (:sign resource)))
              ))))

      ;; Draw resources the player has

      (doto d
        (aset "fillStyle" "#7878C8")
        (.fillRect 0 0 150 (+ 2 (* (inc ICON_SIZE) NUM_RESOURCES)))
        (.strokeRect 0.5 0.5 150 (+ 2 (* (inc ICON_SIZE) NUM_RESOURCES))))
      (doseq [[resource-count rix] (ixfy (:resources game-state))]
        (doseq [ix (range resource-count)]
          (draw-resource-icon d (+ 2 (* (inc ICON_SIZE) ix)) (+ 2 (* (inc ICON_SIZE) rix)) rix 0)))
      )
    )
  )

(defn xy->country-ix [x y]
  ((res :color-ix) (color->text (get-pix (:data (res :map-img)) x y))))

(defn xy->country-name [x y]
  ((res :color->country-name) (color->text (get-pix (:data (res :map-img)) x y))))

(defn map-component [w h game-state]
  (let [f (paint-fn w h)]
    [canvas-comp {:width w :height h
                  :paint f
                  :on-mouse-move
                  (fn [e] (let [{x :x y :y} (relpos e)]
                            (reset! (cursor session/state [:game-state :cc]) (xy->country-ix x y))))
                  :on-mouse-down
                  (fn [e] (let [{x :x y :y} (relpos e)]
                            (let [ix (xy->country-ix x y)]
                              (swap! (cursor session/state [:game-state :countries])
                                     (fn [conts] (if (and (accessible-from? game-state ix)
                                                          (not (contains? conts ix)))
                                                   (if (add-resources! (resources-of-country-ix ix))
                                                     (do (print ix (xy->country-name x y))
                                                         (conj conts ix))
                                                     conts)
                                                   (do (print "nope") conts))))
                              (.preventDefault e))))}
     game-state]))

(defn capitalize-words [ss]
  ;;str.replace(/\w\S*/g, function(txt){return txt.charAt(0).toUpperCase() + txt.substr(1).toLowerCase();});
  (if ss
    (.replace ss (js/RegExp. "\\w\\S*" "g")
              (fn [txt]
                (str (-> txt (.charAt 0) (.toUpperCase))
                     (-> txt (.substr 1) (.toLowerCase)))))
    nil)
  )
(declare init-game-state)
(declare init-ephemeral-state)
(defn home-page []
  (let [info (res :map-pieces-info)
        img (:canvas (res :map-pieces-img))
        w (:width (:orig_image_size info))
        h (:height (:orig_image_size info))]
    (if (and info img)
      [:span
       [map-component w h (session/get :game-state)]
       [:br]
       [:button {:on-click #(init-ephemeral-state)} "Restart Map"]
       [:button {:on-click #(init-game-state)} "New Map"]
       [:span.cc-name (capitalize-words
                       ((res :color->country-name)
                        (get (get-in @session/state [:res :map-pieces-info :colors])
                             (:cc (session/get :game-state)))))]]
      [:span])))

(defn color-bimap->ix-bimap
  "Convert a (color -> color -> 'a) map to a (ix -> ix set) map,
  where cix : color -> ix"
  [cix map]
  (make-map (for [[key value] map
                  :let [ix (cix key)]
                  :when ix]
              [ix (set (for [key2 (keys value)
                             :let [ix2 (cix key2)]
                             :when ix2] ix2))])))

(def initial-country 39) ; urrakeny

(defn distinct-res []
  (let [a (ri NUM_RESOURCES) b (ri (dec NUM_RESOURCES))]
    (if (>= b a) [a (inc b)] [a b])))

(defn bool->int [b] (if b 1 0))

(defn rand-res-for [sum which]
  (let [sign (let [v (get sum which)]
           (cond
             (= v 0) (bool->int (rcoin 0.5))
             (> v 2) 1
             (< v -2) 0
             (> v 0) (bool->int (rcoin 0.9))
             (< v 0) (bool->int (rcoin 0.5))
             ))]
     {:which which
      :sign sign}))

(defn random-resources-for [countries]
  (loop [co countries
         sum (vec (map #(do 0) (range NUM_RESOURCES)))
         res []]
    (if (empty? co)
      [res sum]
      (let [c (first co)
            re (cond
                 (rcoin 0.6) [(rand-res-for sum (ri NUM_RESOURCES))
                               (rand-res-for sum (ri NUM_RESOURCES))
                               (rand-res-for sum (ri NUM_RESOURCES))]
                 (rcoin 0.9) (let [[a b] (distinct-res)]
                               [(rand-res-for sum a)
                                (rand-res-for sum b)])
                 true [(rand-res-for sum (ri NUM_RESOURCES))])]
        (recur (rest co) (reduce add-resource sum re) (conj res [c re]))))))

(defn random-resources-on [countries]
  (let [[proposal costs] (random-resources-for (remove #(= % initial-country) countries))]
    {:country-resources (make-map proposal)
     :map-sum-resources costs}))

(defn init-ephemeral-state []
  (swap! (cursor session/state [:game-state])
         (fn [st] (merge st {:countries #{initial-country}
                             :resources (vec (map #(if (< % 0) (- %) 0)
                                                  (st :map-sum-resources)))}))))

(defn init-game-state []
  (session/put! :game-state (random-resources-on (keys (res :adjacencies))))
  (init-ephemeral-state))

(defn init-state []
  (print "initting")
  (go (let [
            res
            {:map-img (<! (img-bundle-future "map.png"))
             :icons-img (<! (img-bundle-future "icons.png"))
             :map-pieces-info (<! (json-future "built/map-pieces.json"))
             :map-pieces-img (<! (img-bundle-future "built/map-pieces.png"))
             :outline-img (<! (img-future "built/map-outline.png"))}
            res (assoc
                 res :centers
                 (make-map (for [{[x y] :pos} labels/pos->label]
                             [(color->text (get-pix (:data (res :map-img)) x y)) [x y]])))
            res (assoc
                 res :color-ix
                 (make-map (ixfy (:colors (res :map-pieces-info)))))
            res (assoc
                 res :adjacencies
                 (color-bimap->ix-bimap (res :color-ix) (<! (raw-json-future "built/adjacencies.json"))))
            res (assoc
                 res :color->country-name
                 (make-map (map (fn [{[x y] :pos text :text}] [(color->text (get-pix (:data (res :map-img)) x y)) text]) labels/pos->label)))]
        (session/put! :res res)
        (init-game-state))))


;; Initialize app
(defn mount-root []
  (print "mounting")
  (reagent/render [home-page] (.getElementById js/document "app")))

(defn init! []
  (init-state)
  (aset js/document "onkeydown"
        (fn [e]
          (case (.-keyCode e)
            82 (init-game-state)
            83 (init-ephemeral-state)
            (.log js/console (.-keyCode e)))))
  (mount-root))
