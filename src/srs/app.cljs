(ns srs.app
  (:require [clojure.string :as str]
            [open-spaced-repetition.cljc-fsrs.core :as fsrs]
            [srs.cloud :as cloud]
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
        (update-in [:schedule :state] keyword)
        (update :reviews #(or % []))
        (update :revision #(or % 0)))))

(defn parse-backup [raw]
  (let [{:keys [version cards]} (js->clj (.parse js/JSON raw) :keywordize-keys true)]
    (when-not (and (= version 1) (vector? cards))
      (throw (js/Error. "Unsupported backup format")))
    (mapv decode-card cards)))

(defn backup-json [cards]
  (.stringify js/JSON
              (clj->js {:version 1 :cards (mapv encode-card cards)})
              nil 2))

(defn browser-cards []
  (when-let [raw (.getItem js/localStorage storage-key)]
    (parse-backup raw)))

(defn legacy-count []
  (try
    (count (browser-cards))
    (catch :default _ 0)))

(defn initial-state []
  (if (cloud/configured?)
    {:cards [] :user nil :auth-loading? true :busy? false
     :legacy-count (legacy-count) :selected-id nil :revealed? false
     :draft-front "" :draft-back "" :message nil :storage-error nil}
    (try
      {:cards (or (browser-cards) []) :selected-id nil :revealed? false
       :draft-front "" :draft-back "" :message nil :storage-error nil :busy? false}
      (catch :default error
        {:cards [] :selected-id nil :revealed? false
         :draft-front "" :draft-back "" :message nil :busy? false
         :storage-error (str "Could not read saved cards: " (.-message error))}))))

(defonce app-state (atom (initial-state)))
(declare render! choose-next-id)

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

(defn cloud-row [card user-id]
  (-> (encode-card card)
      (select-keys [:id :front :back :schedule :reviews :revision])
      (assoc :user_id user-id)))

(defn load-cloud-page! [user-id offset accumulated]
  (-> (cloud/list-cards! offset)
      (.then (fn [result]
               (when (= user-id (get-in @app-state [:user :id]))
                 (if-let [error (cloud/error-message result)]
                   (set-ui! {:auth-loading? false
                             :message (str "Could not load cards: " error)})
                   (try
                     (let [rows (cloud/result-rows result)
                           cards (into accumulated (map decode-card rows))]
                       (if (= 1000 (count rows))
                         (load-cloud-page! user-id (+ offset 1000) cards)
                         (set-ui! {:cards cards :selected-id nil :revealed? false
                                   :auth-loading? false :message nil})))
                     (catch :default error
                       (set-ui! {:auth-loading? false
                                 :message (str "Invalid saved card: "
                                               (.-message error))}))))))
             (fn [error]
               (when (= user-id (get-in @app-state [:user :id]))
                 (set-ui! {:auth-loading? false
                           :message (str "Could not load cards: "
                                         (.-message error))}))))))

(defn load-cloud! [user-id]
  (load-cloud-page! user-id 0 []))

(defn auth-changed! [user]
  (let [user-id (some-> user (aget "id"))]
    (if user-id
      (when (not= user-id (get-in @app-state [:user :id]))
        (set-ui! {:user {:id user-id :email (aget user "email")}
                  :cards [] :selected-id nil :revealed? false
                  :auth-loading? true :message nil})
        (load-cloud! user-id))
      (set-ui! {:user nil :cards [] :selected-id nil :revealed? false
                :auth-loading? false :busy? false :message nil}))))

(defn create-cloud-card! [card]
  (when-not (:busy? @app-state)
    (let [user-id (get-in @app-state [:user :id])]
      (set-ui! {:busy? true})
      (-> (cloud/insert-cards! [(cloud-row card user-id)])
          (.then (fn [result]
                   (if-let [error (cloud/error-message result)]
                     (set-ui! {:busy? false :message (str "Save failed: " error)})
                     (let [saved (decode-card (first (cloud/result-rows result)))]
                       (when (= user-id (get-in @app-state [:user :id]))
                         (set-ui! {:cards (conj (:cards @app-state) saved)
                                   :selected-id (:id saved) :revealed? false
                                   :draft-front "" :draft-back ""
                                   :busy? false :message nil})))))
                 (fn [error]
                   (set-ui! {:busy? false
                             :message (str "Save failed: " (.-message error))})))))))

(defn review-cloud-card! [card rating]
  (when-not (:busy? @app-state)
    (try
      (let [now (t/now)
            updated (-> card
                        (assoc :schedule
                               (fsrs/repeat-card! (:schedule card) rating now
                                                  fsrs/default-params))
                        (update :reviews conj {:rating (name rating)
                                               :reviewed_at (str now)})
                        (update :revision inc))
            user-id (get-in @app-state [:user :id])]
        (set-ui! {:busy? true})
        (-> (cloud/update-card! (:id card) (:revision card)
                                (select-keys (encode-card updated)
                                             [:schedule :reviews :revision]))
            (.then (fn [result]
                     (if-let [error (cloud/error-message result)]
                       (set-ui! {:busy? false
                                 :message (str "Review failed: " error)})
                       (if-let [row (first (cloud/result-rows result))]
                         (when (= user-id (get-in @app-state [:user :id]))
                           (let [saved (decode-card row)
                                 cards (mapv #(if (= (:id %) (:id saved)) saved %)
                                             (:cards @app-state))]
                             (set-ui! {:cards cards
                                       :selected-id (choose-next-id cards (:id saved))
                                       :revealed? false :busy? false :message nil})))
                         (do
                           (set-ui! {:busy? false
                                     :message "Card changed on another device. Reloaded cards."})
                           (load-cloud! user-id)))))
                   (fn [error]
                     (set-ui! {:busy? false
                               :message (str "Review failed: "
                                             (.-message error))})))))
      (catch :default error
        (set-ui! {:busy? false
                  :message (str "Review failed: " (.-message error))})))))

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
      (if (cloud/configured?)
        (review-cloud-card! card rating)
        (try
          (let [now (t/now)
                updated (-> card
                            (assoc :schedule
                                   (fsrs/repeat-card! (:schedule card) rating now
                                                      fsrs/default-params))
                            (update :reviews conj {:rating (name rating)
                                                   :reviewed_at (str now)}))
                cards (mapv #(if (= (:id %) (:id card)) updated %)
                            (:cards @app-state))]
            (save-cards! cards {:selected-id (choose-next-id cards (:id card))
                                :revealed? false}))
          (catch :default error
            (set-ui! {:message (str "Review failed: " (.-message error))})))))))

(defn make-card! [front back]
  (let [front (str/trim front)
        back (str/trim back)]
    (when (and (seq front) (seq back))
      (let [card {:id (str (random-uuid))
                  :front front
                  :back back
                  :schedule (fsrs/new-card!)
                  :reviews []
                  :revision 0}]
        (if (cloud/configured?)
          (create-cloud-card! card)
          (save-cards! (conj (:cards @app-state) card)
                       {:selected-id (:id card) :revealed? false
                        :draft-front "" :draft-back ""}))))))

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
    (set! (.-disabled node) (boolean (:busy? @app-state)))
    (.addEventListener node "click" handler)
    node))

(defn date-label [card]
  (.toLocaleString (js/Date. (str (get-in card [:schedule :due])))))

(defn download-backup! [cards]
  (let [blob (js/Blob. #js [(backup-json cards)]
                       #js {:type "application/json"})
        url (.createObjectURL js/URL blob)
        link (element "a" nil nil)]
    (set! (.-href link) url)
    (set! (.-download link) "srs-cards.json")
    (.appendChild (.-body js/document) link)
    (.click link)
    (.remove link)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)))

(defn export! []
  (download-backup! (:cards @app-state)))

(defn import-cloud-cards! [cards remove-browser-copy?]
  (when (and (seq cards) (not (:busy? @app-state)))
    (let [user-id (get-in @app-state [:user :id])
          new-cards (mapv #(assoc % :id (str (random-uuid)) :revision 0) cards)]
      (set-ui! {:busy? true})
      (-> (cloud/insert-cards! (mapv #(cloud-row % user-id) new-cards))
          (.then (fn [result]
                   (if-let [error (cloud/error-message result)]
                     (set-ui! {:busy? false
                               :message (str "Import failed: " error)})
                     (when (= user-id (get-in @app-state [:user :id]))
                       (when remove-browser-copy?
                         (.removeItem js/localStorage storage-key))
                       (set-ui! {:cards (into (:cards @app-state) new-cards)
                                 :legacy-count (if remove-browser-copy? 0
                                                   (:legacy-count @app-state))
                                 :busy? false :message nil}))))
                 (fn [error]
                   (set-ui! {:busy? false
                             :message (str "Import failed: "
                                           (.-message error))})))))))

(defn migrate-browser-cards! []
  (try
    (let [cards (browser-cards)]
      (when (and (seq cards)
                 (js/confirm (str "Move " (count cards)
                                  " browser cards into this account?")))
        (import-cloud-cards! cards true)))
    (catch :default error
      (set-ui! {:message (str "Migration failed: " (.-message error))}))))

(defn send-magic-link! [email]
  (when-not (:busy? @app-state)
    (set-ui! {:busy? true})
    (-> (cloud/sign-in! (str/trim email))
        (.then (fn [result]
                 (if-let [error (cloud/error-message result)]
                   (set-ui! {:busy? false
                             :message (str "Sign-in failed: " error)})
                   (set-ui! {:busy? false
                             :message "Check your email for the sign-in link."})))
               (fn [error]
                 (set-ui! {:busy? false
                           :message (str "Sign-in failed: "
                                         (.-message error))}))))))

(defn sign-out! []
  (-> (cloud/sign-out!)
      (.then (fn [result]
               (when-let [error (cloud/error-message result)]
                 (set-ui! {:message (str "Sign-out failed: " error)})))
             (fn [error]
               (set-ui! {:message (str "Sign-out failed: "
                                       (.-message error))})))))

(defn import! [file]
  (when file
    (let [reader (js/FileReader.)]
      (set! (.-onload reader)
            (fn [_]
              (try
                (let [cards (parse-backup (.-result reader))]
                  (if (cloud/configured?)
                    (when (js/confirm (str "Add " (count cards)
                                           " backup cards to this account?"))
                      (import-cloud-cards! cards false))
                    (when (or (empty? (:cards @app-state))
                              (js/confirm "Replace all current cards with this backup?"))
                      (save-cards! cards {:selected-id nil :revealed? false}))))
                (catch :default error
                  (set-ui! {:message (str "Import failed: " (.-message error))})))))
      (.readAsText reader file))))

(defn toolbar []
  (let [bar (element "div" "toolbar" nil)]
    (when (or (not (cloud/configured?)) (:user @app-state))
      (let [picker (element "input" "file-input" nil)
            import-button (button "Import" "button secondary" #(.click picker))]
        (set! (.-type picker) "file")
        (set! (.-accept picker) ".json,application/json")
        (.addEventListener picker "change"
                           (fn [event]
                             (import! (aget (.. event -target -files) 0))
                             (set! (.-value picker) "")))
        (append! bar (button "Export backup" "button secondary" export!)
                 import-button picker)))
    (when (and (cloud/configured?) (:user @app-state))
      (when (pos? (:legacy-count @app-state))
        (append! bar (button (str "Move " (:legacy-count @app-state)
                                  " browser cards")
                             "button secondary" migrate-browser-cards!)))
      (append! bar
               (element "span" "account-email" (get-in @app-state [:user :email]))
               (button "Sign out" "button secondary" sign-out!)))
    bar))

(defn login-form []
  (let [form (element "form" "login-form" nil)
        label (element "label" nil "Email address")
        input (element "input" nil nil)
        submit (element "button" "button" "Send sign-in link")]
    (set! (.-type input) "email")
    (set! (.-required input) true)
    (set! (.-autocomplete input) "email")
    (set! (.-type submit) "submit")
    (set! (.-disabled submit) (boolean (:busy? @app-state)))
    (.addEventListener form "submit"
                       (fn [event]
                         (.preventDefault event)
                         (send-magic-link! (.-value input))))
    (append! label input)
    (append! form
             (element "h2" nil "Sign in to your cards")
             (element "p" "subtitle"
                      "Enter your email. We'll send a link to open your cards on any device.")
             label submit)
    (when (pos? (:legacy-count @app-state))
      (append! form
               (button "Download old browser cards" "button secondary"
                       #(download-backup! (browser-cards)))))
    form))

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
    (set! (.-value front) (:draft-front @app-state))
    (set! (.-value back) (:draft-back @app-state))
    (.addEventListener front "input"
                       #(swap! app-state assoc :draft-front (.. % -target -value)))
    (.addEventListener back "input"
                       #(swap! app-state assoc :draft-back (.. % -target -value)))
    (set! (.-type submit) "submit")
    (set! (.-disabled submit) (boolean (:busy? @app-state)))
    (.addEventListener form "submit"
                       (fn [event]
                         (.preventDefault event)
                         (make-card! (.-value front) (.-value back))))
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
        {:keys [cards message storage-error user auth-loading?]} @app-state
        header (element "header" "site-header" nil)
        layout (element "div" "layout" nil)]
    (set! (.-textContent root) "")
    (append! header (element "div" nil nil))
    (append! (.-firstChild header)
             (element "h1" nil "SRS Cards")
             (element "p" "subtitle" "Study a card, reveal its answer, then rate your recall."))
    (append! header (toolbar))
    (append! root header)
    (when-not (cloud/configured?)
      (append! root
               (element "p" "notice"
                        "Local preview only. Configure Supabase for user accounts and cloud storage.")))
    (when (or message storage-error)
      (append! root (element "p" "notice" (or storage-error message))))
    (cond
      storage-error nil
      (and (cloud/configured?) auth-loading?)
      (append! root (element "p" "empty" "Loading your account..."))
      (and (cloud/configured?) (not user))
      (append! root (login-form))
      :else
      (do
        (append! layout (card-view (current-card))
                 (element "aside" "sidebar" nil))
        (append! (.-lastChild layout) (add-form) (card-list))
        (append! root layout)
        (append! root
                 (element "p" "storage-note"
                          (if (cloud/configured?)
                            (str (count cards) " cards saved to your account.")
                            (str (count cards) " cards saved in this browser. "
                                 "Export a backup before clearing browser data or switching devices."))))))))

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
  (when (cloud/configured?)
    (cloud/listen-auth! auth-changed!))
  (render!))
