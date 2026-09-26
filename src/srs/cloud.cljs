(ns srs.cloud)

(def config (or (aget js/window "SRS_CONFIG") #js {}))
(def url (aget config "supabaseUrl"))
(def publishable-key (aget config "supabasePublishableKey"))

(defn configured? []
  (and (string? url) (not= "" url)
       (string? publishable-key) (not= "" publishable-key)))

(defonce client
  (when (configured?)
    (js-invoke js/supabase "createClient" url publishable-key
               #js {:auth #js {:persistSession true
                               :autoRefreshToken true
                               :detectSessionInUrl true}})))

(defn listen-auth! [handler]
  (js-invoke (aget client "auth") "onAuthStateChange"
             (fn [event session]
               (when (#{"INITIAL_SESSION" "SIGNED_IN" "SIGNED_OUT"
                        "USER_UPDATED"} event)
                 (js/setTimeout #(handler (some-> session (aget "user"))) 0)))))

(defn sign-in! [email]
  (js-invoke (aget client "auth") "signInWithOtp"
             #js {:email email
                  :options #js {:emailRedirectTo
                                (str (.-origin js/location)
                                     (.-pathname js/location))}}))

(defn sign-out! []
  (js-invoke (aget client "auth") "signOut"))

(defn delete-account! []
  (js-invoke client "rpc" "delete_account"))

(defn list-cards! [offset]
  (let [table (js-invoke client "from" "cards")
        query (js-invoke table "select"
                         "id,front,back,deck,schedule,reviews,revision")
        ordered (js-invoke query "order" "created_at" #js {:ascending true})]
    (js-invoke ordered "range" offset (+ offset 999))))

(defn insert-cards! [rows]
  (let [table (js-invoke client "from" "cards")
        query (js-invoke table "insert" (clj->js rows))]
    (js-invoke query "select" "id,front,back,deck,schedule,reviews,revision")))

(defn update-card! [id revision fields]
  (let [table (js-invoke client "from" "cards")
        query (js-invoke table "update" (clj->js fields))
        by-id (js-invoke query "eq" "id" id)
        by-revision (js-invoke by-id "eq" "revision" revision)]
    (js-invoke by-revision "select"
               "id,front,back,deck,schedule,reviews,revision")))

(defn delete-card! [id revision]
  (let [table (js-invoke client "from" "cards")
        query (js-invoke table "delete")
        by-id (js-invoke query "eq" "id" id)
        by-revision (js-invoke by-id "eq" "revision" revision)]
    (js-invoke by-revision "select" "id")))

(defn list-decks! []
  (let [table (js-invoke client "from" "decks")
        query (js-invoke table "select" "name")]
    (js-invoke query "order" "name" #js {:ascending true})))

(defn insert-deck! [user-id name]
  (let [table (js-invoke client "from" "decks")
        query (js-invoke table "insert" #js [#js {:user_id user-id :name name}])]
    (js-invoke query "select" "name")))

(defn rename-deck! [old-name new-name]
  (js-invoke client "rpc" "rename_deck"
             #js {:old_name old-name :new_name new-name}))

(defn delete-deck! [name]
  (js-invoke client "rpc" "delete_deck" #js {:deck_name name}))

(defn merge-decks! [source target]
  (js-invoke client "rpc" "merge_decks"
             #js {:source_name source :target_name target}))

(defn shared-decks! []
  (js-invoke client "rpc" "shared_deck_catalog"))

(defn copy-shared-deck! [owner source target schedule]
  (js-invoke client "rpc" "copy_shared_deck"
             #js {:source_owner owner :source_name source
                  :target_name target :new_schedule (clj->js schedule)}))

(defn error-message [result]
  (some-> result (aget "error") (aget "message")))

(defn result-rows [result]
  (js->clj (aget result "data") :keywordize-keys true))
