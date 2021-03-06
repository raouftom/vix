;; cljs/src/views/feed.cljs: UI implementation for feed management.
;; Copyright 2011-2012, Vixu.com, F.M. (Filip) de Waard <fmw@vixu.com>.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;; 
;; http://www.apache.org/licenses/LICENSE-2.0
;; 
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns vix.views.feed
  (:require [cljs.reader :as reader]
            [vix.document :as document]
            [vix.util :as util]
            [vix.ui :as ui]
            [clojure.set :as set]
            [clojure.string :as string]
            [vix.templates.feed :as tpl]
            [goog.events :as events]
            [goog.events.EventType :as event-type]
            [goog.dom :as dom]
            [goog.dom.classes :as classes]
            [goog.Uri :as Uri]))

(def default-slug-has-invalid-chars-err
  (str "Slugs can only contain '/', '-', '.', alphanumeric characters "
       "and tokens (e.g. {day} and {document-title})."))

(def default-slug-has-consecutive-dashes-or-slashes-err
  "Slugs shouldn't contain any consecutive '-' or '/' characters.")

(def default-slug-initial-slash-required-err
  "The default slug needs to start with a '/'.")

(def default-slug-requires-document-title-err
  "The default slug needs to include a {document-title} token.")

(def default-slug-has-invalid-tokens-err
  "The following slug tokens aren't recognized: ")

(def default-slug-has-unbalanced-braces-err
  "The default slug contains unbalanced '{' and '}' characters.")

(def feed-name-required-err
  "The feed name value is required.")

(def feed-title-required-err
  "The feed title value is required.")

(def feed-name-has-invalid-characters-err
  "The feed name value can only contain '-' and alphanumeric characters.")

(def feed-name-only-allows-dashes-in-body-err
  "The feed name needs to start and end with an alphanumeric character.")

(def feed-name-has-consecutive-dashes-err
  "The feed name shouldn't contain any consecutive '-' characters.")

(def could-not-save-feed-err
  "Something went wrong while trying to save this feed.")

(def feed-update-conflict-err
  (str "This feed has been updated after this page was opened. "
       "Please refresh the page to edit the most recent version."))

(def feed-already-exists-err
  "The provided feed already exists. Please use a different name.")

(defn display-document-list [main-el xhr]
  (ui/render-template main-el
                      tpl/list-documents
                      {:docs (reader/read-string
                              (. xhr (getResponseText)))}))

(defn display-feed-list [main-el feeds]
  (ui/render-template main-el
                        tpl/list-feeds
                        {:feeds feeds
                         :languages (set (map :language-full feeds))}))

(defn list-documents [language feed-name]
  (util/set-page-title! (str "List of documents for feed \"" feed-name "\""))
  (document/get-documents-for-feed
   language
   feed-name
   (fn [e]
     (let [main-el (dom/getElement "main-page")
           xhr (.-target e)
           status (. xhr (getStatus))]
       (if (= status 200)
         (do
           (display-document-list main-el xhr)
           (create-document-list-events language feed-name))
         (ui/render-template main-el tpl/list-documents-error))))))

(defn list-feeds-callback [e]
  (let [main-el (dom/getElement "main-page")
        xhr (.-target e)
        feeds (reader/read-string (. xhr (getResponseText)))
        status (. xhr (getStatus))]
    (if (= status 200)
      (do
        (display-feed-list main-el feeds)
        (create-feed-list-events))
      (ui/render-template main-el tpl/list-feeds-error))))

(defn list-feeds []
  (util/set-page-title! "Feeds overview")
  (document/get-feeds-list list-feeds-callback))

(defn delete-doc-callback [language feed-name e]
  (list-documents language feed-name))

(defn create-document-list-events [language feed-name]
  (util/xhrify-internal-links! (util/get-internal-links!))
  (events/listen (dom/getElement "add-document")
                 "click"
                 (fn [e]
                   (util/navigate (str language "/" feed-name "/new")
                                  "New Document")))

  (ui/trigger-on-class "delete-document"
                       "click"
                       (fn [e]
                         (. e (preventDefault))
                         (let [slug (nth
                                     (string/split (.-id (.-target e)) "_")
                                     2)]
                           (document/delete-document-shortcut
                            slug
                            (partial delete-doc-callback
                                     language
                                     feed-name))))))
(defn create-feed-list-events []
  (util/xhrify-internal-links! (util/get-internal-links!))
  (events/listen (dom/getElement "add-feed")
                 "click"
                 (fn [e]
                   (util/navigate "new-feed") "New Feed"))
  
  ;; converting to vector to avoid issues with doseq and arrays
  (doseq [delete-link (cljs.core.Vector/fromArray
                       (dom/getElementsByTagNameAndClass "a" "delete-feed"))]
    (events/listen delete-link
                   "click"
                   (fn [e]
                     (. e (preventDefault))
                     (let [id-segments (string/split (.-id (.-target e)) ":")
                           language (nth id-segments 1)
                           feed-name (nth id-segments 2)]
                       (document/delete-feed-shortcut language
                                                      feed-name
                                                      list-feeds))))))
(defn get-invalid-tokens [slug]
  (set/difference (set (re-seq #"\{[^\}]{0,}}" slug))
                  #{"{language}"
                    "{day}"
                    "{month}"
                    "{year}"
                    "{document-title}"
                    "{feed-name}"
                    "{ext}"}))

(defn has-unbalanced-braces? [slug]
  (let [braces (re-seq #"[\{\}]" slug)]
    (if (odd? (count braces))
      true
      (pos? (count (filter #(not (= ["{" "}"] %)) (partition 2 braces)))))))

(defn validate-default-slug [e]
  (let [status-el (dom/getElement "status-message")
        slug-el (.-target e)
        slug (.-value slug-el)
        slug-label-el (dom/getElement "default-slug-format-select-label")
        err #(ui/display-error status-el % slug-el slug-label-el)
        invalid-tokens (get-invalid-tokens slug)]
    (cond
     (not (= (first slug) "/"))
       (err default-slug-initial-slash-required-err)
     (re-find #"[^/\-a-zA-Z0-9\{\}\.]" slug)
       (err default-slug-has-invalid-chars-err)
     (not (re-find #"\{document-title\}" slug))
       (err default-slug-requires-document-title-err)
     (has-unbalanced-braces? slug)
       (err default-slug-has-unbalanced-braces-err)
     (pos? (count invalid-tokens))
       (err (str default-slug-has-invalid-tokens-err
                 (apply str (interpose ", " invalid-tokens))))
     (util/has-consecutive-dashes-or-slashes? slug)
       (err default-slug-has-consecutive-dashes-or-slashes-err)
       :else (ui/remove-error status-el slug-el slug-label-el))))


(defn validate-feed-name-and-preview-in-slug [e]
  (let [name-el (.-target e)
        name-val (.-value name-el)
        name-label-el (dom/getElement "name-label")
        status-el (dom/getElement "status-message")
        dsfs-el (dom/getElement "default-slug-format-select")
        err #(ui/display-error status-el % name-el name-label-el)]

    (cond
     (string/blank? name-val)
      (err feed-name-required-err)
     (not (re-matches #"[\-a-zA-Z0-9]+" name-val))
      (err feed-name-has-invalid-characters-err)
     (or (= (first name-val) "-") (= (last name-val) "-"))
      (err feed-name-only-allows-dashes-in-body-err)
     (util/has-consecutive-dashes-or-slashes? name-val)
      (err feed-name-has-consecutive-dashes-err)
     :else
     (ui/remove-error status-el name-el name-label-el))

    (preview-slug!)))

(defn preview-slug! []
  (let [dsfs-el (dom/getElement "default-slug-format-select")
        select-opts (cljs.core.Vector/fromArray (dom/getChildren dsfs-el))]
    (when-not (classes/has (dom/getElement "name") "error")
      (doseq [opt select-opts]
        (dom/setTextContent opt (util/create-slug (.-value opt)
                                                  "document-title"
                                                  (get-feed-value-map!)
                                                  (util/date-now!)
                                                  "ext"))))))

(defn validate-feed! []
  (let [name-el (dom/getElement "name")
        title-el (dom/getElement "title")
        subtitle-el (dom/getElement "subtitle")
        dsf-el (dom/getElement "default-slug-format")
        ddt-el (dom/getElement "default-document-type")
        status-el (dom/getElement "status-message")
        err (partial ui/display-error status-el)]
    (cond
     (string/blank? (.-value name-el))
       (err feed-name-required-err
            name-el
            (dom/getElement "name-label"))
     (string/blank? (.-value title-el))
       (err feed-title-required-err
            (dom/getElement "title")
            (dom/getElement "title-label"))
     :else
       (if (classes/has status-el "error")
         false
         true))))

(defn get-feed-value-map! []
  (let [language (util/pair-from-string (ui/get-form-value "language"))]
    {:name (ui/get-form-value "name")
     :title (ui/get-form-value "title")
     :subtitle (ui/get-form-value "subtitle")
     :language (first language)
     :language-full (last language)
     :default-slug-format (ui/get-form-value "default-slug-format")
     :default-document-type (ui/get-form-value "default-document-type")
     :searchable (string? (ui/get-form-value "searchable"))
     }))

; FIXME: avoid duplication between this and the other 3 xhr callback fns
(defn save-new-feed-xhr-callback [e]
  (let [xhr (.-target e)]
    (if (= (. xhr (getStatus)) 201)
      (let [feed (first (reader/read-string (. xhr (getResponseText))))]
        (util/navigate-replace-state (str "edit-feed/"
                                          (:language feed)
                                          "/"
                                          (:name feed))
                                     (str "Edit feed \""
                                          (:name feed)
                                          "\"")))
      (ui/display-error (dom/getElement "status-message")
                        (if (= (. xhr (getResponseText))
                               "The provided feed already exists.")
                          feed-already-exists-err
                          could-not-save-feed-err)))))

; FIXME: avoid duplication between this and the other 3 xhr callback fns
(defn save-existing-feed-xhr-callback [e]
  (let [xhr (.-target e)]
    (if (= (. xhr (getStatus)) 200)
      (let [feed (first (reader/read-string (. xhr (getResponseText))))]
        (util/navigate-replace-state (str "edit-feed/"
                                          (:language feed)
                                          "/"
                                          (:name feed))
                                     (str "Edit feed \""
                                          (:name feed)
                                          "\"")))
      (ui/display-error (dom/getElement "status-message")
                        (if (= (. xhr (getResponseText))
                               (str "This feed map doesn't contain the most "
                                    "recent :previous-id."))
                          feed-update-conflict-err
                          could-not-save-feed-err)))))

(defn create-feed-form-events
  [status language feed-name & [current-feed-id]]  
  (let [dsf-el (dom/getElement "default-slug-format")
        title-el (dom/getElement "title")
        name-el (dom/getElement "name")]
    (events/listen (dom/getElement "default-slug-format-select")
                   event-type/CHANGE
                   (fn [e]
                     (let [val (.-value (.-target e))]
                       (if (= val "custom")
                         (do
                           (ui/enable-element dsf-el))
                         (do
                           (ui/disable-element dsf-el)
                           (ui/set-form-value dsf-el val))))))

    (events/listen dsf-el event-type/INPUT validate-default-slug)

    (events/listen name-el
                   event-type/INPUT
                   validate-feed-name-and-preview-in-slug)

    ;; remove outdated errors left by save event validation
    (events/listen title-el
                   event-type/INPUT
                   (fn [e]
                     (when-not (string/blank? (.-value title-el))
                       (ui/remove-error (dom/getElement "status-message")
                                        (dom/getElement "title-label")
                                        title-el))))
    
    (events/listen (dom/getElement "save-feed")
                   event-type/CLICK
                   (fn [e]
                     (when (validate-feed!)
                       (let [feed-doc (get-feed-value-map!)]
                         (if (= status :new)
                           (document/append-to-feed
                            (assoc feed-doc :action :create)
                            save-new-feed-xhr-callback)
                           (document/append-to-feed
                            (assoc feed-doc
                              :action :update
                              :previous-id current-feed-id)
                            save-existing-feed-xhr-callback))))))))

(defn render-feed-form [feed]
  (ui/render-template (dom/getElement "main-page")
                      tpl/manage-feed
                      feed)
  (util/xhrify-internal-links! (util/get-internal-links!))

  (when (:language feed)
    (ui/set-form-value (dom/getElement "language")
                       (str "['" (:language feed) "',"
                            "'" (:language-full feed) "']"))))

(defn display-new-feed-form []
  (render-feed-form {:status "new"
                     :name ""
                     :title ""
                     :subtitle ""
                     :language "['en','English']"
                     :default_slug_format
                     "/{language}/{feed-name}/{document-title}"
                     :default_document_type "standard"
                     :searchable true})
  (create-feed-form-events :new nil nil))

(defn display-edit-feed-xhr-callback [language feed-name e]
  (let [xhr (.-target e)
        status (. xhr (getStatus))]
    (if (= status 200)
      (let [feed (first (reader/read-string (. xhr (getResponseText))))]
        (util/set-page-title!
         (str "Edit feed \"" (:title feed) "\""))
        (render-feed-form (assoc feed :status "new"))
        (preview-slug!)
        (let [dsf (:default-slug-format feed)
              select-option (partial ui/set-form-value
                                     (dom/getElement
                                      "default-slug-format-select"))]
          (cond
           (= dsf "/{language}/{feed-name}/{document-title}")
             (select-option "/{language}/{feed-name}/{document-title}")
           (= dsf "/{language}/{feed-name}/{document-title}.{ext}")
             (select-option "/{language}/{feed-name}/{document-title}.{ext}")
           (= dsf "/{language}/{document-title}")
             (select-option "/{language}/{document-title}")
           (= dsf "/{language}/{year}/{month}/{day}/{document-title}")
             (select-option
               "/{language}/{year}/{month}/{day}/{document-title}")
           :else
             (do
               (select-option "custom")
               (ui/enable-element "default-slug-format-select")
               (ui/enable-element "default-slug-format"))))
          
        (ui/disable-element "name")
        (ui/disable-element "language")
        (create-feed-form-events :edit
                                 language
                                 feed-name
                                 (:_id feed)))
      ; else clause
      (ui/render-template (dom/getElement "main-page") tpl/feed-not-found))))

(defn display-edit-feed-form [language feed-name]
  (document/get-feed language
                     feed-name
                     #(display-edit-feed-xhr-callback language
                                                      feed-name
                                                      %)))