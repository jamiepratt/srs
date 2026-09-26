(ns srs.app
  (:require [clojure.string :as str]
            [open-spaced-repetition.cljc-fsrs.core :as fsrs]
            [srs.cloud :as cloud]
            [tick.core :as t]))

(def storage-key "jamiepratt.srs.cards.v2")
(def decks-storage-key "jamiepratt.srs.decks.v1")
(def old-storage-key "jamiepratt.srs.cards.v1")
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
                   (= 6 (:fsrs-version schedule))
                   (contains? schedule :step)
                   (or (nil? (:step schedule))
                       (and (integer? (:step schedule))
                            (<= 0 (:step schedule))))
                   (contains? states (keyword (:state schedule)))
                   (every? number? (map schedule
                                        [:stability :difficulty :elapsed-days
                                         :scheduled-days :reps :lapses]))
                   (string? (:due schedule))
                   (string? (:last-repeat schedule)))
      (throw (js/Error. "Invalid card data")))
    (-> card
        (update :deck #(if (and (string? %) (seq (str/trim %))) % "Default"))
        (update-in [:schedule :due] t/instant)
        (update-in [:schedule :last-repeat] t/instant)
        (update-in [:schedule :state] keyword)
        (update :reviews #(or % []))
        (update :revision #(or % 0)))))

(defn decode-unscheduled-card [card]
  (when-not (and (string? (:id card))
                 (string? (:front card))
                 (string? (:back card))
                 (or (nil? (:deck card)) (string? (:deck card))))
    (throw (js/Error. "Invalid card data")))
  {:id (:id card) :front (:front card) :back (:back card)
   :deck (if (seq (str/trim (or (:deck card) ""))) (:deck card) "Default")
   :schedule (fsrs/new-card!) :reviews [] :revision 0})

(defn parse-backup-data [raw]
  (let [{:keys [version cards deck_name scheduling_data]}
        (js->clj (.parse js/JSON raw) :keywordize-keys true)]
    (when-not (and (= version 2) (vector? cards))
      (throw (js/Error. "An FSRS-6 backup is required")))
    (when (and deck_name
               (not (and (string? deck_name)
                         (<= 1 (count (str/trim deck_name)) 100))))
      (throw (js/Error. "Invalid deck name in backup")))
    {:cards (mapv (if (false? scheduling_data)
                    decode-unscheduled-card decode-card) cards)
     :deck-name (some-> deck_name str/trim)}))

(defn parse-backup [raw]
  (:cards (parse-backup-data raw)))

(defn backup-json
  ([cards] (backup-json cards nil))
  ([cards deck-name] (backup-json cards deck-name true))
  ([cards deck-name scheduling?]
   (.stringify js/JSON
               (clj->js (cond-> {:version 2
                                 :cards (mapv (if scheduling?
                                                encode-card
                                                #(select-keys % [:id :front :back :deck]))
                                              cards)}
                          (not scheduling?) (assoc :scheduling_data false)
                          deck-name (assoc :deck_name deck-name)))
               nil 2)))

(defn browser-cards []
  (when-let [raw (.getItem js/localStorage storage-key)]
    (parse-backup raw)))

(defn browser-decks []
  (when-let [raw (.getItem js/localStorage decks-storage-key)]
    (let [names (js->clj (.parse js/JSON raw))]
      (when (vector? names)
        (filterv #(and (string? %) (<= 1 (count (str/trim %)) 100)) names)))))

(defn available-decks [decks cards]
  (let [names (->> (concat decks (map :deck cards))
                   (filter string?) distinct sort vec)]
    (if (seq names) names ["Default"])))

(defn valid-current-deck [current decks cards]
  (let [options (available-decks decks cards)]
    (if (some #{current} options) current (first options))))

(defn legacy-count []
  (try
    (count (browser-cards))
    (catch :default _ 0)))

(defn initial-state []
  (if (cloud/configured?)
    {:cards [] :user nil :auth-loading? true :busy? false
     :decks ["Default"] :shared-decks [] :current-deck "Default"
     :legacy-count (legacy-count) :selected-id nil :revealed? false
     :export-scheduling? true
     :pending-import nil :import-destination "" :import-new-deck ""
     :draft-front "" :draft-back "" :draft-new-deck "" :draft-rename-deck ""
     :message nil :storage-error nil}
    (try
      (let [cards (or (browser-cards) [])
            decks (or (browser-decks) ["Default"])]
        {:cards cards :decks decks :shared-decks []
         :current-deck (valid-current-deck "Default" decks cards)
         :selected-id nil :revealed? false
         :export-scheduling? true
         :pending-import nil :import-destination "" :import-new-deck ""
         :draft-front "" :draft-back "" :draft-new-deck "" :draft-rename-deck ""
         :message nil :storage-error nil :busy? false})
      (catch :default error
        {:cards [] :decks ["Default"] :shared-decks [] :current-deck "Default"
         :selected-id nil :revealed? false
         :export-scheduling? true
         :pending-import nil :import-destination "" :import-new-deck ""
         :draft-front "" :draft-back "" :draft-new-deck "" :draft-rename-deck ""
         :message nil :busy? false
         :storage-error (str "Could not read saved cards: " (.-message error))}))))

(defonce app-state (atom (initial-state)))
(declare render! choose-next-id)

(defn set-ui! [changes]
  (swap! app-state merge changes)
  (render!))

(defn deck-options []
  (available-decks (:decks @app-state) (:cards @app-state)))

(defn selected-deck-cards []
  (filterv #(= (:current-deck @app-state) (:deck %))
           (:cards @app-state)))

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
      (select-keys [:id :front :back :deck :schedule :reviews :revision])
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
                         (-> (cloud/list-decks!)
                             (.then (fn [deck-result]
                                      (when (= user-id (get-in @app-state [:user :id]))
                                        (if-let [error (cloud/error-message deck-result)]
                                          (set-ui! {:cards cards
                                                    :current-deck (valid-current-deck
                                                                   (:current-deck @app-state)
                                                                   (:decks @app-state) cards)
                                                    :selected-id nil
                                                    :revealed? false :auth-loading? false
                                                    :message (str "Could not load decks: " error)})
                                          (let [decks (mapv :name (cloud/result-rows deck-result))]
                                            (set-ui! {:cards cards :decks decks
                                                      :current-deck (valid-current-deck
                                                                     (:current-deck @app-state)
                                                                     decks cards)
                                                      :selected-id nil :revealed? false
                                                      :auth-loading? false :message nil})))))
                                    (fn [error]
                                      (when (= user-id (get-in @app-state [:user :id]))
                                        (set-ui! {:cards cards
                                                  :current-deck (valid-current-deck
                                                                 (:current-deck @app-state)
                                                                 (:decks @app-state) cards)
                                                  :selected-id nil
                                                  :revealed? false :auth-loading? false
                                                  :message (str "Could not load decks: "
                                                                (.-message error))})))))))
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

(defn load-shared-decks! [user-id]
  (-> (cloud/shared-decks!)
      (.then (fn [result]
               (when (= user-id (get-in @app-state [:user :id]))
                 (if-let [error (cloud/error-message result)]
                   (set-ui! {:message (str "Could not load shared decks: " error)})
                   (set-ui! {:shared-decks (cloud/result-rows result)}))))
             (fn [error]
               (when (= user-id (get-in @app-state [:user :id]))
                 (set-ui! {:message (str "Could not load shared decks: "
                                         (.-message error))}))))))

(defn auth-changed! [user]
  (let [user-id (some-> user (aget "id"))]
    (if user-id
      (when (not= user-id (get-in @app-state [:user :id]))
        (set-ui! {:user {:id user-id :email (aget user "email")}
                  :cards [] :decks ["Default"] :shared-decks []
                  :current-deck "Default"
                  :editing nil :busy? false :selected-id nil :revealed? false
                  :pending-import nil :import-destination "" :import-new-deck ""
                  :auth-loading? true :message nil})
        (load-cloud! user-id)
        (load-shared-decks! user-id))
      (set-ui! {:user nil :cards [] :decks ["Default"] :shared-decks []
                :current-deck "Default" :editing nil :selected-id nil :revealed? false
                :pending-import nil :import-destination "" :import-new-deck ""
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
                                       :selected-id (choose-next-id cards (:id saved)
                                                                    (:current-deck @app-state))
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
  (let [cards (selected-deck-cards)
        selected-id (:selected-id @app-state)]
    (or (some #(when (= selected-id (:id %)) %) cards)
        (first (sort-by due-ms (filter due? cards))))))

(defn choose-next-id [cards reviewed-id deck]
  (:id (first (sort-by due-ms
                       (filter #(and (= deck (:deck %))
                                     (not= reviewed-id (:id %)) (due? %)) cards)))))

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
            (save-cards! cards {:selected-id (choose-next-id cards (:id card)
                                                             (:current-deck @app-state))
                                :revealed? false}))
          (catch :default error
            (set-ui! {:message (str "Review failed: " (.-message error))})))))))

(defn change-cloud-card! [card fields]
  (let [user-id (get-in @app-state [:user :id])]
    (set-ui! {:busy? true :message nil})
    (-> (if fields
          (cloud/update-card! (:id card) (:revision card)
                              (assoc fields :revision (inc (:revision card))))
          (cloud/delete-card! (:id card) (:revision card)))
        (.then (fn [result]
                 (when (= user-id (get-in @app-state [:user :id]))
                   (if-let [error (cloud/error-message result)]
                     (set-ui! {:busy? false :message (str (if fields "Save failed: " "Delete failed: ") error)})
                     (if-let [row (first (cloud/result-rows result))]
                       (let [cards (if fields
                                     (let [saved (decode-card row)]
                                       (mapv #(if (= (:id %) (:id card)) saved %)
                                             (:cards @app-state)))
                                     (filterv #(not= (:id %) (:id card)) (:cards @app-state)))]
                         (set-ui! {:cards cards :editing nil :busy? false :revealed? false
                                   :selected-id (when fields (:id card))
                                   :message (if fields "Card saved." "Card deleted.")}))
                       (-> (load-cloud! user-id)
                           (.then (fn [_]
                                    (when (= user-id (get-in @app-state [:user :id]))
                                      (set-ui! {:busy? false
                                                :message (str "Card changed on another device. "
                                                              (if-let [error (:message @app-state)]
                                                                (str error " Reload the page before trying again.")
                                                                (str "Cards refreshed. "
                                                                     (if fields
                                                                       "Cancel editing to see the latest version before trying again."
                                                                       "Check the latest card before deleting again."))))})))))))))
               (fn [error]
                 (when (= user-id (get-in @app-state [:user :id]))
                   (set-ui! {:busy? false :message (str (if fields "Save failed: " "Delete failed: ") (.-message error))})))))))

(defn change-browser-card! [card fields]
  (try
    (let [cards (or (browser-cards) [])
          current (some #(when (= (:id %) (:id card)) %) cards)]
      (if (not= card current)
        (set-ui! {:cards cards :selected-id nil :revealed? false
                  :message (str "Card changed in another tab. Cards refreshed. "
                                (if fields "Cancel editing to see the latest version before trying again."
                                    "Check the latest card before deleting again."))})
        (let [updated (when fields (-> card (merge fields) (update :revision inc)))
              cards (if fields
                      (mapv #(if (= (:id %) (:id card)) updated %) cards)
                      (filterv #(not= (:id %) (:id card)) cards))]
          (when (save-cards! cards {:editing nil :selected-id (when fields (:id card))
                                    :revealed? false})
            (set-ui! {:message (if fields "Card saved." "Card deleted.")})))))
    (catch :default error
      (set-ui! {:message (str "Save failed: " (.-message error))}))))

(defn delete-card! [card]
  (when (and (not (:busy? @app-state))
             (js/confirm "Delete this card and its review history? This cannot be undone."))
    (if (cloud/configured?)
      (change-cloud-card! card nil)
      (change-browser-card! card nil))))

(defn edit-card! [card front back]
  (when-not (:busy? @app-state)
    (let [front (str/trim front)
          back (str/trim back)]
      (if (or (empty? front) (empty? back))
        (set-ui! {:message "Front and back cannot be empty."})
        (if (cloud/configured?)
          (change-cloud-card! card {:front front :back back})
          (change-browser-card! card {:front front :back back}))))))

(defn make-card! [front back]
  (let [front (str/trim front)
        back (str/trim back)]
    (when (and (seq front) (seq back))
      (let [card {:id (str (random-uuid))
                  :front front
                  :back back
                  :deck (:current-deck @app-state)
                  :schedule (fsrs/new-card!)
                  :reviews []
                  :revision 0}]
        (if (cloud/configured?)
          (create-cloud-card! card)
          (save-cards! (conj (:cards @app-state) card)
                       {:selected-id (:id card) :revealed? false
                        :draft-front "" :draft-back ""}))))))

(defn create-deck! [input]
  (when-not (:busy? @app-state)
    (let [name (str/trim input)
          existing (some #(when (= (str/lower-case %) (str/lower-case name)) %)
                         (deck-options))]
      (cond
        (or (empty? name) (> (count name) 100))
        (set-ui! {:message "Deck name must be 1 to 100 characters."})

        existing
        (set-ui! {:current-deck existing :selected-id nil :revealed? false
                  :draft-new-deck "" :draft-rename-deck "" :message nil})

        (cloud/configured?)
        (let [user-id (get-in @app-state [:user :id])]
          (set-ui! {:busy? true})
          (-> (cloud/insert-deck! user-id name)
              (.then (fn [result]
                       (when (= user-id (get-in @app-state [:user :id]))
                         (if-let [error (cloud/error-message result)]
                           (set-ui! {:busy? false
                                     :message (str "Could not create deck: " error)})
                           (set-ui! {:decks (conj (:decks @app-state) name)
                                     :current-deck name :selected-id nil
                                     :revealed? false :busy? false :message nil
                                     :draft-new-deck "" :draft-rename-deck ""}))))
                     (fn [error]
                       (set-ui! {:busy? false
                                 :message (str "Could not create deck: "
                                               (.-message error))})))))

        :else
        (try
          (let [names (conj (deck-options) name)]
            (.setItem js/localStorage decks-storage-key
                      (.stringify js/JSON (clj->js names)))
            (set-ui! {:decks names :current-deck name
                      :selected-id nil :revealed? false :message nil
                      :draft-new-deck "" :draft-rename-deck ""}))
          (catch :default error
            (set-ui! {:message (str "Could not create deck: "
                                    (.-message error))})))))))

(defn rename-deck! [input]
  (when-not (:busy? @app-state)
    (let [old-name (:current-deck @app-state)
          name (str/trim input)
          duplicate (some #(when (= (str/lower-case %) (str/lower-case name)) %)
                          (deck-options))]
      (cond
        (or (empty? name) (> (count name) 100))
        (set-ui! {:message "Deck name must be 1 to 100 characters."})

        (= old-name name)
        (set-ui! {:message "Enter a different deck name."})

        duplicate
        (set-ui! {:message "A deck with that name already exists."})

        (cloud/configured?)
        (let [user-id (get-in @app-state [:user :id])]
          (set-ui! {:busy? true})
          (-> (cloud/rename-deck! old-name name)
              (.then (fn [result]
                       (when (= user-id (get-in @app-state [:user :id]))
                         (if-let [error (cloud/error-message result)]
                           (set-ui! {:busy? false
                                     :message (str "Could not rename deck: " error)})
                           (do
                             (set-ui! {:current-deck name :selected-id nil
                                       :revealed? false :draft-rename-deck ""
                                       :auth-loading? true :busy? false :message nil})
                             (load-cloud! user-id)))))
                     (fn [error]
                       (set-ui! {:busy? false
                                 :message (str "Could not rename deck: "
                                               (.-message error))})))))

        :else
        (try
          (let [cards (mapv #(if (= old-name (:deck %))
                               (-> % (assoc :deck name) (update :revision inc)) %)
                            (:cards @app-state))
                names (mapv #(if (= old-name %) name %) (deck-options))]
            (.setItem js/localStorage storage-key (backup-json cards))
            (.setItem js/localStorage decks-storage-key
                      (.stringify js/JSON (clj->js names)))
            (set-ui! {:cards cards :decks names :current-deck name
                      :draft-rename-deck "" :message nil}))
          (catch :default error
            (set-ui! {:message (str "Could not rename deck: "
                                    (.-message error))})))))))

(defn delete-deck-blocked-reason []
  (cond
    (seq (selected-deck-cards)) "Move all cards to another deck before deleting this deck."
    (< (count (deck-options)) 2) "Keep at least one deck. Create another deck first."))

(defn delete-deck! []
  (when (and (not (:busy? @app-state)) (nil? (delete-deck-blocked-reason)))
    (let [name (:current-deck @app-state)]
      (when (js/confirm (str "Delete empty deck \"" name "\"? This cannot be undone."))
        (if (cloud/configured?)
          (let [user-id (get-in @app-state [:user :id])]
            (set-ui! {:busy? true})
            (-> (cloud/delete-deck! name)
                (.then (fn [result]
                         (when (= user-id (get-in @app-state [:user :id]))
                           (if-let [error (cloud/error-message result)]
                             (do
                               (set-ui! {:busy? false :auth-loading? true})
                               (-> (load-cloud! user-id)
                                   (.then #(when (= user-id (get-in @app-state [:user :id]))
                                             (set-ui! {:message (str "Could not delete deck: " error)})))))
                             (do
                               (set-ui! {:busy? false :auth-loading? true
                                         :selected-id nil :revealed? false
                                         :draft-rename-deck ""})
                               (load-cloud! user-id)))))
                       (fn [error]
                         (when (= user-id (get-in @app-state [:user :id]))
                           (set-ui! {:busy? false
                                     :message (str "Could not delete deck: " (.-message error))}))))))
          (try
            (let [cards (or (browser-cards) [])
                  decks (available-decks (or (browser-decks) []) cards)
                  reason (cond
                           (some #(= name (:deck %)) cards)
                           "Move all cards to another deck before deleting this deck."
                           (< (count decks) 2)
                           "Keep at least one deck. Create another deck first.")]
              (if reason
                (set-ui! {:cards cards :decks decks
                          :current-deck (valid-current-deck name decks cards)
                          :message reason})
                (let [names (filterv #(not= name %) decks)]
                  (.setItem js/localStorage decks-storage-key
                            (.stringify js/JSON (clj->js names)))
                  (set-ui! {:cards cards :decks names :current-deck (first names)
                            :selected-id nil :revealed? false :draft-rename-deck ""
                            :message nil}))))
            (catch :default error
              (set-ui! {:message (str "Could not delete deck: " (.-message error))}))))))))

(defn merge-deck! [target]
  (let [source (:current-deck @app-state)]
    (when (and (not (:busy? @app-state))
               (some #{target} (deck-options)) (not= source target)
               (js/confirm (str "Move all cards from \"" source "\" into \""
                                target "\" and remove \"" source "\"?")))
      (if (cloud/configured?)
        (let [user-id (get-in @app-state [:user :id])]
          (set-ui! {:busy? true})
          (-> (cloud/merge-decks! source target)
              (.then (fn [result]
                       (when (= user-id (get-in @app-state [:user :id]))
                         (if-let [error (cloud/error-message result)]
                           (set-ui! {:busy? false
                                     :message (str "Could not merge decks: " error)})
                           (do
                             (set-ui! {:current-deck target :selected-id nil
                                       :revealed? false :busy? false :auth-loading? true
                                       :merge-target "" :message nil})
                             (load-cloud! user-id)))))
                     (fn [error]
                       (set-ui! {:busy? false
                                 :message (str "Could not merge decks: "
                                               (.-message error))})))))
        (try
          (let [cards (mapv #(if (= source (:deck %))
                               (-> % (assoc :deck target) (update :revision inc)) %)
                            (or (browser-cards) []))
                decks (filterv #(not= source %) (deck-options))]
            (.setItem js/localStorage storage-key (backup-json cards))
            (.setItem js/localStorage decks-storage-key
                      (.stringify js/JSON (clj->js decks)))
            (set-ui! {:cards cards :decks decks :current-deck target
                      :selected-id nil :revealed? false :merge-target ""
                      :message nil}))
          (catch :default error
            (set-ui! {:message (str "Could not merge decks: "
                                    (.-message error))})))))))

(defn copy-name [name]
  (let [taken (set (map str/lower-case (deck-options)))]
    (if-not (contains? taken (str/lower-case name))
      name
      (loop [n 1]
        (let [suffix (str " (copy " n ")")
              candidate (str (subs name 0 (min (count name)
                                               (- 100 (count suffix)))) suffix)]
          (if (contains? taken (str/lower-case candidate))
            (recur (inc n)) candidate))))))

(defn copy-shared-deck-to-account! [{:keys [owner_id deck_name]}]
  (when (and (:user @app-state) (not (:busy? @app-state)))
    (let [user-id (get-in @app-state [:user :id])
          target (copy-name deck_name)
          schedule (:schedule (encode-card {:schedule (fsrs/new-card!)}))]
      (set-ui! {:busy? true})
      (-> (cloud/copy-shared-deck! owner_id deck_name target schedule)
          (.then (fn [result]
                   (when (= user-id (get-in @app-state [:user :id]))
                     (if-let [error (cloud/error-message result)]
                       (set-ui! {:busy? false
                                 :message (str "Could not copy shared deck: " error)})
                       (do
                         (set-ui! {:busy? false :auth-loading? true
                                   :current-deck target :selected-id nil
                                   :revealed? false :message nil})
                         (load-cloud! user-id)))))
                 (fn [error]
                   (set-ui! {:busy? false
                             :message (str "Could not copy shared deck: "
                                           (.-message error))})))))))

(defn move-card! [card deck]
  (when (and (not (:busy? @app-state)) (not= deck (:deck card)))
    (let [updated (-> card (assoc :deck deck) (update :revision inc))
          old-deck (:current-deck @app-state)]
      (if (cloud/configured?)
        (let [user-id (get-in @app-state [:user :id])]
          (set-ui! {:busy? true})
          (-> (cloud/update-card! (:id card) (:revision card)
                                  (select-keys updated [:deck :revision]))
              (.then (fn [result]
                       (when (= user-id (get-in @app-state [:user :id]))
                         (if-let [error (cloud/error-message result)]
                           (set-ui! {:busy? false
                                     :message (str "Move failed: " error)})
                           (if-let [row (first (cloud/result-rows result))]
                             (let [saved (decode-card row)
                                   cards (mapv #(if (= (:id %) (:id saved)) saved %)
                                               (:cards @app-state))]
                               (set-ui! {:cards cards
                                         :selected-id (choose-next-id cards (:id saved)
                                                                      old-deck)
                                         :revealed? false :busy? false :message nil}))
                             (do
                               (set-ui! {:busy? false
                                         :message "Card changed on another device. Reloaded cards."})
                               (load-cloud! user-id))))))
                     (fn [error]
                       (set-ui! {:busy? false
                                 :message (str "Move failed: "
                                               (.-message error))})))))
        (let [cards (mapv #(if (= (:id %) (:id card)) updated %)
                          (:cards @app-state))]
          (save-cards! cards {:selected-id (choose-next-id cards (:id card)
                                                           old-deck)
                              :revealed? false}))))))

(defn element [tag class-name content]
  (let [node (.createElement js/document tag)]
    (when class-name (set! (.-className node) class-name))
    (when content (set! (.-textContent node) content))
    node))

(defn card-fragment [content]
  (.sanitize js/DOMPurify content
             #js {:USE_PROFILES #js {:html true}
                  :RETURN_DOM_FRAGMENT true
                  :FORBID_TAGS #js ["form" "input" "button" "textarea" "select" "option"]
                  :FORBID_ATTR #js ["style"]}))

(defn card-text [content]
  (let [node (element "div" "card-text" nil)]
    (.appendChild node (card-fragment content))
    node))

(defonce card-audio (atom {:key nil :player nil}))

(defn card-audio-path [card]
  (when (= "Noc Komety" (:deck card))
    (some-> (aget js/window "SRS_CARD_AUDIO")
            (aget (:front card)))))

(defn play-card-audio! []
  (when-let [player (:player @card-audio)]
    (try
      (set! (.-currentTime player) 0)
      (when-let [playing (.play player)]
        (.catch playing (fn [_] nil)))
      (catch :default _ nil))))

(defn sync-card-audio! [card]
  (let [path (card-audio-path card)
        key (when path [(:id card) (:front card)])]
    (when (not= key (:key @card-audio))
      (when-let [old-player (:player @card-audio)]
        (.pause old-player))
      (if path
        (let [player (js/Audio. path)]
          (reset! card-audio {:key key :player player})
          (play-card-audio!))
        (reset! card-audio {:key nil :player nil})))))

(defn card-summary [content]
  (let [fragment (card-fragment content)]
    (doseq [line-break (array-seq (.querySelectorAll fragment "br"))]
      (.replaceWith line-break (.createTextNode js/document " ")))
    (doseq [block (array-seq (.querySelectorAll fragment "p, div, li, h1, h2, h3, h4, h5, h6, blockquote"))]
      (.before block (.createTextNode js/document " "))
      (.after block (.createTextNode js/document " ")))
    (let [summary (str/trim (str/replace (.-textContent fragment) #"\s+" " "))]
      (if (seq summary) summary "Untitled card"))))

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

(defn download-backup!
  ([cards] (download-backup! cards "srs-cards.json" nil true))
  ([cards filename deck-name scheduling?]
   (let [blob (js/Blob. #js [(backup-json cards deck-name scheduling?)]
                        #js {:type "application/json"})
         url (.createObjectURL js/URL blob)
         link (element "a" nil nil)]
     (set! (.-href link) url)
     (set! (.-download link) filename)
     (.appendChild (.-body js/document) link)
     (.click link)
     (.remove link)
     (js/setTimeout #(.revokeObjectURL js/URL url) 1000))))

(defn export! []
  (download-backup! (:cards @app-state) "srs-cards.json" nil
                    (:export-scheduling? @app-state)))

(defn export-current-deck! []
  (let [name (:current-deck @app-state)
        slug (str/replace (str/lower-case name) #"[^a-z0-9]+" "-")]
    (download-backup! (selected-deck-cards)
                      (str "srs-" (if (seq slug) slug "deck") ".json")
                      name (:export-scheduling? @app-state))))

(defn import-cloud-cards!
  ([cards remove-browser-copy?]
   (import-cloud-cards! cards remove-browser-copy? nil))
  ([cards remove-browser-copy? selected-deck]
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
                                  :decks (available-decks (:decks @app-state) new-cards)
                                  :current-deck (or selected-deck (:current-deck @app-state))
                                  :pending-import nil
                                  :legacy-count (if remove-browser-copy? 0
                                                    (:legacy-count @app-state))
                                  :busy? false :message nil}))))
                  (fn [error]
                    (set-ui! {:busy? false
                              :message (str "Import failed: "
                                            (.-message error))}))))))))

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
    (let [reader (js/FileReader.)
          user-id (get-in @app-state [:user :id])]
      (swap! app-state assoc :pending-import nil)
      (set! (.-onload reader)
            (fn [_]
              (when (= user-id (get-in @app-state [:user :id]))
                (try
                  (set-ui! {:pending-import (assoc (parse-backup-data (.-result reader))
                                                   :filename (.-name file))
                            :import-destination "" :import-new-deck "" :message nil})
                  (catch :default error
                    (set-ui! {:pending-import nil
                              :message (str "Import failed: " (.-message error))}))))))
      (set! (.-onerror reader)
            #(set-ui! {:pending-import nil :message "Import failed: Could not read the file."}))
      (.readAsText reader file))))

(defn confirm-import! []
  (when (and (:pending-import @app-state) (not (:busy? @app-state)))
    (let [{:keys [cards deck-name]} (:pending-import @app-state)
          choice (:import-destination @app-state)
          new-name (str/trim (:import-new-deck @app-state))
          destination (cond
                        (= choice "source") nil
                        (= choice "new") new-name
                        (str/starts-with? choice "deck:") (subs choice 5))
          valid-existing? (or (not (str/starts-with? choice "deck:"))
                              (some #{destination} (deck-options)))
          source-cards (if deck-name
                         (mapv #(assoc % :deck deck-name) cards)
                         cards)
          target-cards (if destination
                         (mapv #(assoc % :deck destination) cards)
                         source-cards)
          empty-deck (when (empty? cards)
                       (if (= choice "source") deck-name destination))]
      (cond
        (empty? choice)
        (set-ui! {:message "Choose where to import the cards."})

        (and (= choice "source") (empty? cards) (not deck-name))
        (set-ui! {:message "This backup has no cards or deck to import."})

        (and (= choice "new") (or (empty? new-name) (> (count new-name) 100)))
        (set-ui! {:message "Deck name must be 1 to 100 characters."})

        (and (= choice "new")
             (some #(= (str/lower-case %) (str/lower-case new-name))
                   (deck-options)))
        (set-ui! {:message "That deck already exists. Choose it from the list."})

        (not valid-existing?)
        (set-ui! {:message "Choose an existing deck."})

        empty-deck
        (if (some #{empty-deck} (deck-options))
          (set-ui! {:pending-import nil :message "No cards to import."})
          (do (set-ui! {:pending-import nil})
              (create-deck! empty-deck)))

        (cloud/configured?)
        (import-cloud-cards! target-cards false destination)

        :else
        (try
          (let [new-cards (mapv #(assoc % :id (str (random-uuid)) :revision 0)
                                target-cards)
                existing (or (browser-cards) [])]
            (save-cards! (into existing new-cards)
                         {:decks (available-decks (:decks @app-state) new-cards)
                          :current-deck (or destination (:current-deck @app-state))
                          :pending-import nil :selected-id nil :revealed? false}))
          (catch :default error
            (set-ui! {:message (str "Import failed: " (.-message error))})))))))

(defn deck-picker []
  (let [label (element "label" "deck-label" "Deck")
        select (element "select" "deck-select" nil)]
    (doseq [name (deck-options)]
      (let [option (element "option" nil name)]
        (set! (.-value option) name)
        (append! select option)))
    (set! (.-value select) (:current-deck @app-state))
    (set! (.-disabled select) (boolean (:busy? @app-state)))
    (.addEventListener select "change"
                       #(set-ui! {:current-deck (.. % -target -value)
                                  :editing nil :selected-id nil :revealed? false
                                  :draft-rename-deck ""}))
    (append! label select)
    label))

(defn nav-link [label href]
  (let [link (element "a" "button secondary nav-link" label)]
    (set! (.-href link) href)
    link))

(defn toolbar [manage?]
  (let [bar (element "nav" "toolbar" nil)]
    (when (or (not (cloud/configured?)) (:user @app-state))
      (if manage?
        (append! bar (nav-link "Back to study" "#study"))
        (append! bar (deck-picker) (nav-link "Manage decks" "#manage-decks"))))
    bar))

(defn deck-action-form [title description field-label placeholder draft-key action-label action!]
  (let [form (element "form" "management-panel" nil)
        label (element "label" "management-label" field-label)
        input (element "input" nil nil)
        submit (element "button" "button" action-label)]
    (set! (.-type input) "text")
    (set! (.-maxLength input) 100)
    (set! (.-required input) true)
    (set! (.-placeholder input) placeholder)
    (set! (.-value input) (get @app-state draft-key))
    (set! (.-type submit) "submit")
    (set! (.-disabled submit) (boolean (:busy? @app-state)))
    (.addEventListener input "input"
                       #(swap! app-state assoc draft-key (.. % -target -value)))
    (.addEventListener form "submit"
                       (fn [event]
                         (.preventDefault event)
                         (action! (.-value input))))
    (append! label input)
    (append! form (element "h2" nil title)
             (element "p" "format-hint" description) label submit)
    form))

(defn import-destination-panel []
  (when-let [{:keys [cards deck-name filename]} (:pending-import @app-state)]
    (let [panel (element "div" "import-preview" nil)
          source-names (distinct (map :deck cards))
          label (element "label" "management-label" "Import into")
          select (element "select" "import-destination" nil)
          choices (cond-> [["" "Choose a destination"]]
                    (or deck-name (seq cards))
                    (conj ["source" (if deck-name
                                      (str "Keep deck name from file: " deck-name)
                                      (str "Keep original deck names (" (count source-names) ")"))]))
          choices (into choices
                        (concat (map #(vector (str "deck:" %) (str "Existing deck: " %))
                                     (deck-options))
                                [["new" "Create a new deck"]]))]
      (append! panel (element "h3" nil "Where should these cards go?")
               (element "p" "format-hint"
                        (str filename ": " (count cards) " cards. "
                             (if (cloud/configured?)
                               "They will be added to your account."
                               "They will be added to this browser."))))
      (doseq [[value title] choices]
        (let [option (element "option" nil title)]
          (set! (.-value option) value)
          (append! select option)))
      (set! (.-value select) (:import-destination @app-state))
      (set! (.-disabled select) (boolean (:busy? @app-state)))
      (.addEventListener select "change"
                         #(set-ui! {:import-destination (.. % -target -value)
                                    :message nil}))
      (append! label select)
      (append! panel label)
      (when (= "new" (:import-destination @app-state))
        (let [new-label (element "label" "management-label" "New deck name")
              input (element "input" nil nil)]
          (set! (.-type input) "text")
          (set! (.-maxLength input) 100)
          (set! (.-value input) (:import-new-deck @app-state))
          (.addEventListener input "input"
                             #(swap! app-state assoc :import-new-deck
                                     (.. % -target -value)))
          (append! new-label input)
          (append! panel new-label)))
      (append! panel
               (button "Import cards" "button" confirm-import!)
               (button "Cancel import" "button secondary"
                       #(set-ui! {:pending-import nil :import-destination ""
                                  :import-new-deck "" :message nil})))
      panel)))

(defn manage-decks-page []
  (let [page (element "div" "management-page" nil)
        intro (element "div" "management-intro" nil)
        grid (element "div" "management-grid" nil)
        current (element "section" "management-panel" nil)
        merge-panel (element "section" "management-panel" nil)
        merge-label (element "label" "management-label" "Merge selected deck into")
        merge-select (element "select" "merge-destination" nil)
        backups (element "section" "management-panel" nil)
        picker (element "input" "file-input" nil)]
    (append! intro (element "h2" nil "Manage decks")
             (element "p" "subtitle" "Organize decks and keep a copy of your cards."))
    (append! current (element "h2" nil "Selected deck")
             (deck-picker)
             (element "p" "format-hint deck-count"
                      (str (count (selected-deck-cards)) " cards in this deck.")))
    (let [reason (delete-deck-blocked-reason)
          delete-button (button "Delete selected deck" "button secondary" delete-deck!)]
      (set! (.-disabled delete-button) (boolean (or reason (:busy? @app-state))))
      (append! current
               (element "p" "format-hint"
                        (or reason "Only empty decks can be deleted. Cards are never deleted."))
               delete-button))
    (append! grid (deck-action-form "Create a deck" "Start a new, empty deck."
                                    "New deck name" "Name your new deck"
                                    :draft-new-deck "Create deck" create-deck!)
             (deck-action-form "Rename selected deck"
                               (str "Change the name of " (:current-deck @app-state) ".")
                               "New name for selected deck" "Enter a different name"
                               :draft-rename-deck "Rename deck" rename-deck!))
    (doseq [name (remove #{(:current-deck @app-state)} (deck-options))]
      (let [option (element "option" nil name)]
        (set! (.-value option) name)
        (append! merge-select option)))
    (set! (.-value merge-select) (or (some #{(:merge-target @app-state)}
                                           (map #(.-value %) (array-seq (.-options merge-select))))
                                     (some-> merge-select .-firstChild .-value)
                                     ""))
    (.addEventListener merge-select "change"
                       #(swap! app-state assoc :merge-target (.. % -target -value)))
    (append! merge-label merge-select)
    (let [submit (element "button" "button" "Merge decks")]
      (set! (.-type submit) "button")
      (set! (.-disabled submit) (or (:busy? @app-state)
                                    (< (count (deck-options)) 2)))
      (.addEventListener submit "click"
                         #(merge-deck! (.-value merge-select)))
      (append! merge-panel (element "h2" nil "Merge decks")
               (element "p" "format-hint"
                        "Move all cards into the chosen deck, then remove the selected deck. Review history stays with each card.")
               merge-label submit))
    (append! grid merge-panel)
    (set! (.-type picker) "file")
    (set! (.-accept picker) ".json,application/json")
    (.addEventListener picker "change"
                       (fn [event]
                         (import! (aget (.. event -target -files) 0))
                         (set! (.-value picker) "")))
    (append! backups (element "h2" nil "Backups"))
    (append! backups (element "p" "format-hint"
                              "Download all cards or only the selected deck. Import a JSON backup to restore cards."))
    (let [label (element "label" "export-option" nil)
          checkbox (element "input" nil nil)]
      (set! (.-type checkbox) "checkbox")
      (set! (.-checked checkbox) (:export-scheduling? @app-state))
      (.addEventListener checkbox "change"
                         #(swap! app-state assoc :export-scheduling?
                                 (.. % -target -checked)))
      (append! label checkbox (.createTextNode js/document " Include scheduling data and review history"))
      (append! backups label))
    (let [actions (element "div" "management-actions" nil)]
      (append! actions (button "Export selected deck" "button secondary" export-current-deck!)
               (button "Export all cards" "button secondary" export!)
               (button "Import backup" "button secondary" #(.click picker)))
      (append! backups actions picker))
    (append! backups (import-destination-panel))
    (when (and (cloud/configured?) (:user @app-state))
      (let [account (element "section" "management-panel" nil)
            library (element "section" "management-panel" nil)]
        (append! library (element "h2" nil "Shared decks")
                 (element "p" "format-hint"
                          "Copy a shared deck into your account. Your review schedule starts fresh and stays private."))
        (doseq [shared (:shared-decks @app-state)]
          (let [row (element "div" "shared-deck-row" nil)
                title (element "span" nil
                               (str (:deck_name shared) " · " (:card_count shared) " cards"))]
            (append! row title
                     (button "Copy deck" "button secondary"
                             #(copy-shared-deck-to-account! shared)))
            (append! library row)))
        (append! account (element "h2" nil "Account")
                 (element "p" "account-email" (get-in @app-state [:user :email])))
        (when (pos? (:legacy-count @app-state))
          (append! account (button (str "Move " (:legacy-count @app-state)
                                        " browser cards")
                                   "button secondary" migrate-browser-cards!)))
        (append! account (button "Sign out" "button secondary" sign-out!))
        (append! page intro current grid library backups account)))
    (when-not (and (cloud/configured?) (:user @app-state))
      (append! page intro current grid backups))
    page))

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

(defn collapsible-panel [class-name title state-key content]
  (let [panel (element "details" class-name nil)
        heading (element "summary" nil title)]
    (set! (.-open panel) (boolean (get @app-state state-key)))
    (.addEventListener panel "toggle"
                       #(swap! app-state assoc state-key (.-open panel)))
    (append! panel heading content)
    panel))

(defn add-form []
  (let [form (element "form" "add-form" nil)
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
    (append! form (element "p" "format-hint"
                           (str "Adding to " (:current-deck @app-state) "."))
             front-label back-label
             (element "p" "format-hint" "HTML fragments are supported. Scripts and unsafe attributes are removed.")
             submit)
    (collapsible-panel "add-card-panel" "Add a card" :add-card-open? form)))

(defn edit-card-form [card]
  (let [form (element "form" "edit-card management-panel" nil)
        front (element "textarea" nil nil)
        back (element "textarea" nil nil)
        front-label (element "label" nil "Front")
        back-label (element "label" nil "Back")
        submit (element "button" "button" "Save changes")]
    (doseq [[field key] [[front :front] [back :back]]]
      (set! (.-value field) (get-in @app-state [:editing key]))
      (set! (.-rows field) 5)
      (set! (.-disabled field) (boolean (:busy? @app-state)))
      (set! (.-required field) true)
      (.addEventListener field "input"
                         #(swap! app-state assoc-in [:editing key] (.. % -target -value))))
    (set! (.-type submit) "submit")
    (set! (.-disabled submit) (boolean (:busy? @app-state)))
    (.addEventListener form "submit"
                       (fn [event]
                         (.preventDefault event)
                         (edit-card! card (.-value front) (.-value back))))
    (append! front-label front)
    (append! back-label back)
    (append! form (element "h2" nil "Edit card")
             (element "p" "format-hint" "Edit HTML or text. Schedule and review history are kept.")
             front-label back-label submit
             (button "Cancel editing" "button secondary" #(set-ui! {:editing nil :message nil})))
    form))

(defn card-view [card]
  (let [panel (element "section" "study-card" nil)]
    (if card
      (let [revealed? (:revealed? @app-state)
            label (element "p" "eyebrow"
                           (str (if (due? card) "Due now" "Review early")
                                " · " (name (get-in card [:schedule :state]))))
            front (card-text (:front card))
            due (element "p" "due" (str "Scheduled: " (date-label card)))]
        (append! panel label front)
        (when (card-audio-path card)
          (append! panel
                   (button "Play pronunciation" "button secondary audio-replay"
                           play-card-audio!)))
        (if revealed?
          (let [answer (element "div" "answer" nil)
                buttons (element "div" "ratings" nil)]
            (append! answer (element "p" "eyebrow" "Answer")
                     (card-text (:back card)))
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
               (element "p" "empty" (if (seq (selected-deck-cards))
                                      "All caught up. Select a card to review early."
                                      "Add a card to get started."))))
    panel))

(defn card-options [card]
  (let [content (element "div" "card-options-content" nil)
        move-label (element "label" "move-label" "Move card to deck")
        move-select (element "select" "deck-select" nil)
        actions (element "div" "card-actions" nil)]
    (doseq [name (deck-options)]
      (let [option (element "option" nil name)]
        (set! (.-value option) name)
        (append! move-select option)))
    (set! (.-value move-select) (:deck card))
    (set! (.-disabled move-select) (boolean (:busy? @app-state)))
    (.addEventListener move-select "change"
                       #(move-card! card (.. % -target -value)))
    (append! move-label move-select)
    (append! actions
             (button "Edit card" "button secondary"
                     #(set-ui! {:editing {:card card :front (:front card) :back (:back card)}
                                :message nil}))
             (button "Delete card" "button danger" #(delete-card! card)))
    (append! content move-label actions)
    (let [panel (collapsible-panel "card-options-panel" "Card options"
                                   :card-options-open? content)]
      (append! (.querySelector panel "summary")
               (element "span" "card-options-front"
                        (str "Card: " (card-summary (:front card)))))
      panel)))

(defn grammar-card? [card]
  (boolean (when card
             (.querySelector (card-fragment (:back card)) ".grammar-xray"))))

(defn grammar-key []
  (let [panel (element "section" "grammar-key" nil)
        cases (element "div" "grammar-key-row" nil)
        symbols (element "div" "grammar-key-row" nil)]
    (doseq [[case label] [["nom" "nominative"] ["acc" "accusative"]
                          ["ins" "instrumental"] ["gen" "genitive"]
                          ["loc" "locative"] ["dat" "dative"]
                          ["voc" "!! vocative"]]]
      (append! cases (element "span" (str "grammar-case grammar-" case) label)))
    (doseq [label ["♂ masculine" "♀ feminine" "⚧ neuter"
                   "⚙️♂ inanimate" "🐶♂ non-human" "🙎‍♂️♂ human"]]
      (append! symbols (element "span" nil label)))
    (let [plural (element "span" nil nil)]
      (append! plural (element "u" nil "plural"))
      (append! symbols plural (element "span" nil "¹ ² ³ person")
               (element "span" "grammar-focus-key" "highlighted = card form")))
    (append! panel (element "h2" nil "Grammar x-ray key") cases symbols
             (element "p" "grammar-key-note"
                      "Person on possessives means the possessor; on verbs, the subject."))
    panel))

(defn card-list []
  (let [panel (element "section" "card-list" nil)
        cards (sort-by due-ms (selected-deck-cards))
        selected-id (:id (current-card))]
    (append! panel (element "h2" nil (str "Cards · " (count cards))))
    (doseq [card cards]
      (append! panel
               (button (str (card-summary (:front card)) (if (due? card) " · due" ""))
                       (str "list-card" (when (= selected-id (:id card)) " selected"))
                       #(set-ui! {:selected-id (:id card) :revealed? false :editing nil}))))
    panel))

(defn render! []
  (let [root (.getElementById js/document "app")
        {:keys [cards message storage-error user auth-loading?]} @app-state
        manage? (= "#manage-decks" (.-hash js/location))
        header (element "header" "site-header" nil)
        layout (element "div" "layout" nil)
        study-column (element "div" "study-column" nil)]
    (set! (.-textContent root) "")
    (append! header (element "div" nil nil))
    (append! (.-firstChild header)
             (element "h1" nil "SRS Cards")
             (element "p" "subtitle"
                      (if manage?
                        "Keep your decks and backups organized."
                        "Study a card, reveal its answer, then rate your recall.")))
    (append! header (toolbar manage?))
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
      manage?
      (append! root (manage-decks-page))
      :else
      (let [card (current-card)]
        (append! study-column (if-let [editing (:editing @app-state)]
                                (edit-card-form (:card editing))
                                (card-view card)))
        (when (grammar-card? card)
          (append! study-column (grammar-key)))
        (append! layout study-column
                 (element "aside" "sidebar" nil))
        (when card
          (append! (.-lastChild layout) (card-options card)))
        (append! (.-lastChild layout) (add-form) (card-list))
        (append! root layout)
        (append! root
                 (element "p" "storage-note"
                          (if (cloud/configured?)
                            (str (count (selected-deck-cards)) " cards in "
                                 (:current-deck @app-state) "; " (count cards)
                                 " total saved to your account.")
                            (str (count (selected-deck-cards)) " cards in "
                                 (:current-deck @app-state) "; " (count cards)
                                 " total saved in this browser. "
                                 "Export a backup before clearing browser data or switching devices."))))))
    (sync-card-audio!
     (when (and (not storage-error) (not manage?)
                (or (not (cloud/configured?)) (and user (not auth-loading?)))
                (not (:editing @app-state)))
       (current-card)))))

(defn handle-key! [event]
  (let [tag (.. event -target -tagName)
        typing? (contains? #{"INPUT" "TEXTAREA" "SELECT" "BUTTON"} tag)]
    (when (and (not= "#manage-decks" (.-hash js/location))
               (not typing?) (not (:editing @app-state)) (not (:busy? @app-state))
               (not (.-repeat event)))
      (cond
        (and (= (.-code event) "Space") (current-card)
             (not (:revealed? @app-state)))
        (do (.preventDefault event) (set-ui! {:revealed? true}))

        (and (:revealed? @app-state)
             (contains? #{"Digit1" "Digit2" "Digit3" "Digit4"} (.-code event)))
        (do (.preventDefault event)
            (review! (nth ratings (- (js/parseInt (.-key event)) 1))))))))

(defn init! []
  (try
    (.removeItem js/localStorage old-storage-key)
    (catch :default _ nil))
  (.addEventListener js/document "keydown" handle-key!)
  (.addEventListener js/window "hashchange" #(set-ui! {:editing nil}))
  (when (cloud/configured?)
    (cloud/listen-auth! auth-changed!))
  (render!))
