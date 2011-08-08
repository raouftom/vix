(ns vix.core
  (:require [vix.views.editor :as editor]
            [vix.views.feed :as feed]
            [goog.events :as events]
            [goog.history.EventType :as event-type]
            [goog.history.Html5History :as Html5History]
            [goog.Uri :as Uri]
            [goog.dom :as dom]))

(def h (atom nil))

(defn get-path [uri]
  (let [uri-obj (new goog.Uri uri false)]
    (.getPath uri-obj uri-obj)))

(defn get-internal-links! []
  (filter #(= (.substr (get-path (.href %)) 0 7) "/admin/")
          (cljs.core.Vector/fromArray (.getElementsByTagName (js* "document") "a"))))

; FIXME: figure out while only nested calls (e.g. in create-document-list-events
; and render-editor-template) work and replace with a centralized call
(defn xhrify-internal-links! [link-elements]
  (doseq [element link-elements]
    (events/listen element
                   "click"
                   (fn [e]
                     ; FIXME: ugly (js*) call
                     (js* "e.preventDefault()")
                     (navigate (.substr (get-path (.href (.target e))) 7)
                               (.title (.target e)))))))

(defn start-history! [uri-path]
  (compare-and-set! h nil (new goog.history.Html5History))
  (.setUseFragment @h false)
  (.setPathPrefix @h "/admin/")
  (.setEnabled @h true)
  (events/listen @h
                 event-type/NAVIGATE
                 (fn [e]
                   (routes (js* "document.location.pathname")))))

(defn routes [uri-path]
  (cond
   (re-matches #"^/admin/[^/]+/new$" uri-path) (editor/start :new uri-path)
   (re-matches #"^/admin/[^/]+/edit.+" uri-path) (editor/start :edit uri-path)
   (re-matches #"^/admin/[^/]+/overview$" uri-path) (feed/list-documents uri-path)
   :else (navigate-replace-state "blog/overview" "Blog overview"))
  nil)

(defn navigate [token title]
  (.setToken @h token title))

(defn navigate-replace-state [token title]
  (.replaceToken @h token title))

(defn start-app [uri-path]
  (start-history!) ; in Chrome this triggers an event, leading to a (routes) call
  (routes uri-path))