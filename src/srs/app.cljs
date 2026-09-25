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

(defn parse-backup [raw]
  (let [{:keys [version cards]} (js->clj (.parse js/JSON raw) :keywordize-keys true)]
    (when-not (and (= version 2) (vector? cards))
      (throw (js/Error. "An FSRS-6 backup is required")))
    (mapv decode-card cards)))

(defn backup-json [cards]
  (.stringify js/JSON
              (clj->js {:version 2 :cards (mapv encode-card cards)})
              nil 2))

(defn browser-cards []
  (when-let [raw (.getItem js/localStorage storage-key)]
    (parse-backup raw)))

(defn browser-decks []
  (when-let [raw (.getItem js/localStorage decks-storage-key)]
    (let [names (js->clj (.parse js/JSON raw))]
      (when (vector? names)
        (filterv #(and (string? %) (<= 1 (count (str/trim %)) 100)) names)))))

(defn legacy-count []
  (try
    (count (browser-cards))
    (catch :default _ 0)))

(defn initial-state []
  (if (cloud/configured?)
    {:cards [] :user nil :auth-loading? true :busy? false
     :decks ["Default"] :current-deck "Default"
     :legacy-count (legacy-count) :selected-id nil :revealed? false
     :draft-front "" :draft-back "" :draft-deck "" :creating-deck? false
     :message nil :storage-error nil}
    (try
      {:cards (or (browser-cards) []) :decks (or (browser-decks) ["Default"])
       :current-deck "Default" :selected-id nil :revealed? false
       :draft-front "" :draft-back "" :draft-deck "" :creating-deck? false
       :message nil :storage-error nil :busy? false}
      (catch :default error
        {:cards [] :decks ["Default"] :current-deck "Default"
         :selected-id nil :revealed? false
         :draft-front "" :draft-back "" :draft-deck "" :creating-deck? false
         :message nil :busy? false
         :storage-error (str "Could not read saved cards: " (.-message error))}))))

(defonce app-state (atom (initial-state)))
(declare render! choose-next-id)

(defn set-ui! [changes]
  (swap! app-state merge changes)
  (render!))

(defn deck-options []
  (->> (concat ["Default"] (:decks @app-state)
               (map :deck (:cards @app-state)))
       (filter string?) distinct sort vec))

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
                                          (set-ui! {:cards cards :selected-id nil
                                                    :revealed? false :auth-loading? false
                                                    :message (str "Could not load decks: " error)})
                                          (set-ui! {:cards cards
                                                    :decks (mapv :name (cloud/result-rows deck-result))
                                                    :selected-id nil :revealed? false
                                                    :auth-loading? false :message nil}))))
                                    (fn [error]
                                      (when (= user-id (get-in @app-state [:user :id]))
                                        (set-ui! {:cards cards :selected-id nil
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

(defn auth-changed! [user]
  (let [user-id (some-> user (aget "id"))]
    (if user-id
      (when (not= user-id (get-in @app-state [:user :id]))
        (set-ui! {:user {:id user-id :email (aget user "email")}
                  :cards [] :decks ["Default"] :current-deck "Default"
                  :selected-id nil :revealed? false
                  :auth-loading? true :message nil})
        (load-cloud! user-id))
      (set-ui! {:user nil :cards [] :decks ["Default"]
                :current-deck "Default" :selected-id nil :revealed? false
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
                  :creating-deck? false :draft-deck "" :message nil})

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
                                     :creating-deck? false :draft-deck ""}))))
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
                      :creating-deck? false :draft-deck ""}))
          (catch :default error
            (set-ui! {:message (str "Could not create deck: "
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
  ([cards] (download-backup! cards "srs-cards.json"))
  ([cards filename]
   (let [blob (js/Blob. #js [(backup-json cards)]
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
  (download-backup! (:cards @app-state)))

(defn export-current-deck! []
  (let [name (:current-deck @app-state)
        slug (str/replace (str/lower-case name) #"[^a-z0-9]+" "-")]
    (download-backup! (selected-deck-cards)
                      (str "srs-" (if (seq slug) slug "deck") ".json"))))

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

(defn new-deck-form []
  (let [form (element "form" "new-deck-form" nil)
        label (element "label" nil "New deck name")
        input (element "input" nil nil)
        submit (element "button" "button" "Create")]
    (set! (.-type input) "text")
    (set! (.-required input) true)
    (set! (.-maxLength input) 100)
    (set! (.-value input) (:draft-deck @app-state))
    (.addEventListener input "input"
                       #(swap! app-state assoc :draft-deck (.. % -target -value)))
    (set! (.-type submit) "submit")
    (set! (.-disabled submit) (boolean (:busy? @app-state)))
    (.addEventListener form "submit"
                       (fn [event]
                         (.preventDefault event)
                         (create-deck! (.-value input))))
    (append! label input)
    (append! form label submit
             (button "Cancel" "button secondary"
                     #(set-ui! {:creating-deck? false :draft-deck ""})))
    form))

(defn toolbar []
  (let [bar (element "div" "toolbar" nil)]
    (when (or (not (cloud/configured?)) (:user @app-state))
      (let [picker (element "input" "file-input" nil)
            import-button (button "Import" "button secondary" #(.click picker))
            deck-label (element "label" "deck-label" "Deck")
            deck-select (element "select" "deck-select" nil)
            deck-export (button "Export current deck" "button secondary"
                                export-current-deck!)]
        (set! (.-type picker) "file")
        (set! (.-accept picker) ".json,application/json")
        (set! (.-disabled deck-export) (empty? (selected-deck-cards)))
        (doseq [name (deck-options)]
          (let [option (element "option" nil name)]
            (set! (.-value option) name)
            (append! deck-select option)))
        (set! (.-value deck-select) (:current-deck @app-state))
        (.addEventListener deck-select "change"
                           #(set-ui! {:current-deck (.. % -target -value)
                                      :selected-id nil :revealed? false}))
        (append! deck-label deck-select)
        (.addEventListener picker "change"
                           (fn [event]
                             (import! (aget (.. event -target -files) 0))
                             (set! (.-value picker) "")))
        (append! bar (button "Export backup" "button secondary" export!)
                 deck-export deck-label
                 (if (:creating-deck? @app-state)
                   (new-deck-form)
                   (button "New deck" "button secondary"
                           #(set-ui! {:creating-deck? true :message nil})))
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
    (append! form title
             (element "p" "format-hint"
                      (str "Adding to " (:current-deck @app-state) "."))
             front-label back-label
             (element "p" "format-hint" "HTML fragments are supported. Scripts and unsafe attributes are removed.")
             submit)))

(defn card-view [card]
  (let [panel (element "section" "study-card" nil)]
    (if card
      (let [revealed? (:revealed? @app-state)
            move-label (element "label" "move-label" "Move card to deck")
            move-select (element "select" "deck-select" nil)
            label (element "p" "eyebrow"
                           (str (if (due? card) "Due now" "Review early")
                                " · " (name (get-in card [:schedule :state]))))
            front (card-text (:front card))
            due (element "p" "due" (str "Scheduled: " (date-label card)))]
        (doseq [name (deck-options)]
          (let [option (element "option" nil name)]
            (set! (.-value option) name)
            (append! move-select option)))
        (set! (.-value move-select) (:deck card))
        (set! (.-disabled move-select) (boolean (:busy? @app-state)))
        (.addEventListener move-select "change"
                           #(move-card! card (.. % -target -value)))
        (append! move-label move-select)
        (append! panel label front)
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
        (append! panel due move-label))
      (append! panel
               (element "p" "empty" (if (seq (selected-deck-cards))
                                      "All caught up. Select a card to review early."
                                      "Add a card to get started."))))
    panel))

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
                       #(set-ui! {:selected-id (:id card) :revealed? false}))))
    panel))

(defn render! []
  (let [root (.getElementById js/document "app")
        {:keys [cards message storage-error user auth-loading?]} @app-state
        header (element "header" "site-header" nil)
        layout (element "div" "layout" nil)
        study-column (element "div" "study-column" nil)]
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
      (let [card (current-card)]
        (append! study-column (card-view card))
        (when (grammar-card? card)
          (append! study-column (grammar-key)))
        (append! layout study-column
                 (element "aside" "sidebar" nil))
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
                                 "Export a backup before clearing browser data or switching devices."))))))))

(defn handle-key! [event]
  (let [tag (.. event -target -tagName)
        typing? (contains? #{"INPUT" "TEXTAREA" "SELECT"} tag)]
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
  (try
    (.removeItem js/localStorage old-storage-key)
    (catch :default _ nil))
  (.addEventListener js/document "keydown" handle-key!)
  (when (cloud/configured?)
    (cloud/listen-auth! auth-changed!))
  (render!))
