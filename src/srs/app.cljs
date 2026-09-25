(ns srs.app
  (:require [clojure.string :as str]
            [open-spaced-repetition.cljc-fsrs.core :as fsrs]
            [tick.core :as t]))

(def storage-key "jamiepratt.srs.cards.v1")
(def ratings [:again :hard :good :easy])
(def states #{:new :learning :review :relearning})

(defn encode-card [card]
  (-> card
      (update-in [:schedule :due] str)
      (update-in [:schedule :last-repeat] str)
      (update-in [:schedule :state] name)))

(defn decode-card [card]
  (let [schedule (:schedule card)]
    (when-not (and (string? (:id card))
                   (string? (:front card))
                   (string? (:back card))
                   (map? schedule)
                   (contains? states (keyword (:state schedule)))
                   (every? number? (map schedule
                                        [:stability :difficulty :elapsed-days
                                         :scheduled-days :reps :lapses]))
                   (string? (:due schedule))
                   (string? (:last-repeat schedule)))
      (throw (js/Error. "Invalid card data")))
    (-> card
        (update-in [:schedule :due] t/instant)
        (update-in [:schedule :last-repeat] t/instant)
        (update-in [:schedule :state] keyword))))

(defn parse-backup [raw]
  (let [{:keys [version cards]} (js->clj (.parse js/JSON raw) :keywordize-keys true)]
    (when-not (and (= version 1) (vector? cards))
      (throw (js/Error. "Unsupported backup format")))
    (mapv decode-card cards)))

(defn backup-json [cards]
  (.stringify js/JSON
              (clj->js {:version 1 :cards (mapv encode-card cards)})
              nil 2))

(defn initial-state []
  (try
    (let [raw (.getItem js/localStorage storage-key)
          cards (if raw (parse-backup raw) [])]
      {:cards cards :selected-id nil :revealed? false :message nil
       :storage-error nil})
    (catch :default error
      {:cards [] :selected-id nil :revealed? false
       :message nil
       :storage-error (str "Could not read saved cards: " (.-message error))})))

(defonce app-state (atom (initial-state)))
(declare render!)

(defn set-ui! [changes]
  (swap! app-state merge changes)
  (render!))

(defn save-cards! [cards changes]
  (try
    (.setItem js/localStorage storage-key (backup-json cards))
    (swap! app-state merge changes {:cards cards :message nil})
    (render!)
    true
    (catch :default error
      (set-ui! {:message (str "Save failed: " (.-message error))})
      false)))

(defn due-ms [card]
  (.parse js/Date (str (get-in card [:schedule :due]))))

(defn due? [card]
  (<= (due-ms card) (.now js/Date)))

(defn current-card []
  (let [{:keys [cards selected-id]} @app-state]
    (or (some #(when (= selected-id (:id %)) %) cards)
        (first (sort-by due-ms (filter due? cards))))))

(defn choose-next-id [cards reviewed-id]
  (:id (first (sort-by due-ms
                       (filter #(and (not= reviewed-id (:id %)) (due? %)) cards)))))

(defn review! [rating]
  (when-let [card (current-card)]
    (when (:revealed? @app-state)
      (try
        (let [updated (assoc card :schedule
                             (fsrs/repeat-card! (:schedule card) rating))
              cards (mapv #(if (= (:id %) (:id card)) updated %) (:cards @app-state))]
          (save-cards! cards {:selected-id (choose-next-id cards (:id card))
                              :revealed? false}))
        (catch :default error
          (set-ui! {:message (str "Review failed: " (.-message error))}))))))

(defn make-card! [front back]
  (let [front (str/trim front)
        back (str/trim back)]
    (when (and (seq front) (seq back))
      (let [card {:id (str (random-uuid))
                  :front front
                  :back back
                  :schedule (fsrs/new-card!)}]
        (save-cards! (conj (:cards @app-state) card)
                     {:selected-id (:id card) :revealed? false})))))

(defn element [tag class-name content]
  (let [node (.createElement js/document tag)]
    (when class-name (set! (.-className node) class-name))
    (when content (set! (.-textContent node) content))
    node))

(defn append! [parent & children]
  (doseq [child children]
    (when child (.appendChild parent child)))
  parent)

(defn button [label class-name handler]
  (let [node (element "button" class-name label)]
    (set! (.-type node) "button")
    (.addEventListener node "click" handler)
    node))

(defn date-label [card]
  (.toLocaleString (js/Date. (str (get-in card [:schedule :due])))))

(defn export! []
  (let [blob (js/Blob. #js [(backup-json (:cards @app-state))]
                       #js {:type "application/json"})
        url (.createObjectURL js/URL blob)
        link (element "a" nil nil)]
    (set! (.-href link) url)
    (set! (.-download link) "srs-cards.json")
    (.appendChild (.-body js/document) link)
    (.click link)
    (.remove link)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)))

(defn import! [file]
  (when file
    (let [reader (js/FileReader.)]
      (set! (.-onload reader)
            (fn [_]
              (try
                (let [cards (parse-backup (.-result reader))]
                  (when (or (empty? (:cards @app-state))
                            (js/confirm "Replace all current cards with this backup?"))
                    (save-cards! cards {:selected-id nil :revealed? false})))
                (catch :default error
                  (set-ui! {:message (str "Import failed: " (.-message error))})))))
      (.readAsText reader file))))

(defn toolbar []
  (let [bar (element "div" "toolbar" nil)
        picker (element "input" "file-input" nil)
        import-button (button "Import" "button secondary" #(.click picker))]
    (set! (.-type picker) "file")
    (set! (.-accept picker) ".json,application/json")
    (.addEventListener picker "change"
                       (fn [event]
                         (import! (aget (.. event -target -files) 0))
                         (set! (.-value picker) "")))
    (append! bar (button "Export backup" "button secondary" export!)
             import-button picker)))

(defn add-form []
  (let [form (element "form" "add-form" nil)
        title (element "h2" nil "Add a card")
        front (element "textarea" nil nil)
        back (element "textarea" nil nil)
        front-label (element "label" nil "Front")
        back-label (element "label" nil "Back")
        submit (element "button" "button" "Add card")]
    (set! (.-rows front) 2)
    (set! (.-rows back) 2)
    (set! (.-required front) true)
    (set! (.-required back) true)
    (set! (.-type submit) "submit")
    (.addEventListener form "submit"
                       (fn [event]
                         (.preventDefault event)
                         (when (make-card! (.-value front) (.-value back))
                           (.reset form))))
    (append! front-label front)
    (append! back-label back)
    (append! form title front-label back-label submit)))

(defn card-view [card]
  (let [panel (element "section" "study-card" nil)]
    (if card
      (let [revealed? (:revealed? @app-state)
            label (element "p" "eyebrow"
                           (str (if (due? card) "Due now" "Review early")
                                " · " (name (get-in card [:schedule :state]))))
            front (element "div" "card-text" (:front card))
            due (element "p" "due" (str "Scheduled: " (date-label card)))]
        (append! panel label front)
        (if revealed?
          (let [answer (element "div" "answer" nil)
                buttons (element "div" "ratings" nil)]
            (append! answer (element "p" "eyebrow" "Answer")
                     (element "div" "card-text" (:back card)))
            (doseq [[rating label] (map vector ratings
                                        ["1 Again" "2 Hard" "3 Good" "4 Easy"])]
              (append! buttons
                       (button label (str "button rating " (name rating))
                               #(review! rating))))
            (append! panel answer buttons))
          (append! panel
                   (button "Show answer · Space" "button reveal"
                           #(set-ui! {:revealed? true}))))
        (append! panel due))
      (append! panel
               (element "p" "empty" (if (seq (:cards @app-state))
                                      "All caught up. Select a card to review early."
                                      "Add a card to get started."))))
    panel))

(defn card-list []
  (let [panel (element "section" "card-list" nil)
        cards (sort-by due-ms (:cards @app-state))
        selected-id (:id (current-card))]
    (append! panel (element "h2" nil (str "Cards · " (count cards))))
    (doseq [card cards]
      (append! panel
               (button (str (:front card) (if (due? card) " · due" ""))
                       (str "list-card" (when (= selected-id (:id card)) " selected"))
                       #(set-ui! {:selected-id (:id card) :revealed? false}))))
    panel))

(defn render! []
  (let [root (.getElementById js/document "app")
        {:keys [cards message storage-error]} @app-state
        header (element "header" "site-header" nil)
        layout (element "div" "layout" nil)]
    (set! (.-textContent root) "")
    (append! header (element "div" nil nil))
    (append! (.-firstChild header)
             (element "h1" nil "SRS Cards")
             (element "p" "subtitle" "Study a card, reveal its answer, then rate your recall."))
    (append! header (toolbar))
    (append! root header)
    (when (or message storage-error)
      (append! root (element "p" "notice" (or storage-error message))))
    (when-not storage-error
      (append! layout (card-view (current-card))
               (element "aside" "sidebar" nil))
      (append! (.-lastChild layout) (add-form) (card-list))
      (append! root layout)
      (append! root
               (element "p" "storage-note"
                        (str (count cards) " cards saved in this browser. "
                             "Export a backup before clearing browser data or switching devices."))))))

(defn handle-key! [event]
  (let [tag (.. event -target -tagName)
        typing? (contains? #{"INPUT" "TEXTAREA"} tag)]
    (when (and (not typing?) (not (.-repeat event)))
      (cond
        (and (= (.-code event) "Space") (current-card)
             (not (:revealed? @app-state)))
        (do (.preventDefault event) (set-ui! {:revealed? true}))

        (and (:revealed? @app-state)
             (contains? #{"Digit1" "Digit2" "Digit3" "Digit4"} (.-code event)))
        (do (.preventDefault event)
            (review! (nth ratings (- (js/parseInt (.-key event)) 1))))))))

(defn init! []
  (.addEventListener js/document "keydown" handle-key!)
  (render!))
