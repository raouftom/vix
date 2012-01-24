;; test/vix/test/lucene.clj tests for lucene code.
;;
;; Copyright 2011-2012, F.M. (Filip) de Waard <fmw@vix.io>.
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

(ns vix.test.lucene
  (:use [vix.lucene] :reload)
  (:use [clojure.test]
        [clojure.contrib.java-utils :only (delete-file-recursively)]
        [clojure.contrib.reflect :only (get-field)]
        [vix.db :only (datetime-string-to-long)])
  (:import [org.apache.lucene.search QueryWrapperFilter]
           [org.apache.lucene.index IndexWriter IndexWriterConfig$OpenMode]
           [org.apache.lucene.search ScoreDoc])
  (:require [couchdb [client :as couchdb]]
            [clj-time.coerce :as time-coerce]))

(def dummy-docs [{:_id "7a26ec145efeb5768e102c85a710cd1c",
                  :_rev "29-9477bcea8056b447bb16591ca866f36a",
                  :slug "/2011/11/4/bar",
                  :content (str "<div><p>ffff</p>"
                                "<img src=\"/images/me-small.png\" "
                                "alt=\"me small\" "
                                "title=\"me small\">"
                                "</div>")
                  :language "en"
                  :title "bar"
                  :published "2011-11-04T09:16:52.253Z"
                  :updated "2011-11-04T12:15:34.590Z"
                  :type "document"
                  :feed "blog"
                  :draft false
                  }
                 {:_id "f18aad9d90d56c7c1f7df057790030c1"
                  :_rev "10-f425c894644ccd600ae303638c268db0"
                  :slug "/2012/1/15/hic-sunt-dracones",
                  :content (str "<p>Here be dragons!</p>")
                  :language "en"
                  :title "Hic sunt dracones"
                  :published "2012-01-15T14:48:52.253Z"
                  :updated nil
                  :type "document"
                  :feed "blog"
                  :draft true
                  }
                 {:_id "0493464e36a373aa446d0bbf33001834"
                  :_rev "1-55a9c22c986da734607df6aed48c7be7"
                  :slug "/2012/1/16/brora",
                  :content (str "<p>Brora is highly regarded whisky "
                                "distillery known for its 'farmy',  "
                                "lightly peated style. Sadly, the "
                                "old Brora distillery was shut down in "
                                "1983, although Clynelish still distills "
                                "spirit in the same general area.")
                  :language "en"
                  :title "Brora!"
                  :published "2012-01-16T11:56:16.253Z"
                  :updated "2012-01-16T13:36:24.253Z"
                  :type "document"
                  :feed "blog"
                  :draft false
                  }
                 {:_id "7a26ec145efeb5768e102c85a710cd1c"
                  :_rev "29-9477bcea8056b447bb16591ca866f36a"
                  :slug "/2012/1/16/caol-ila",
                  :content (str "<p>"
                                "Caol Ila is a single malt whisky "
                                "distilled on Islay (pronounce as 'Isla')."
                                "It is a popular component in blends, "
                                "including the Johnny Walker range from "
                                "Diageo which is also the current owner of "
                                "the distillery. However, it is sublime "
                                "when judged on its own merit as a  "
                                "single malt whisky, known for a somewhat "
                                "lighter style compared to its heavily "
                                "peated neighbours on the isle of Islay."
                                "</p>")
                  :language "en"
                  :title "Caol Ila"
                  :published "2012-01-16T13:33:45.253Z"
                  :updated nil
                  :type "document"
                  :feed "whisky"
                  :draft true
                  }])

(def dummy-docs-extended
  (concat dummy-docs
          (map #(assoc (first %)
                  :title
                  (str (:title (first %)) " " (last %)))
               (partition
                2
                (interleave
                 (take 10 (repeat (last dummy-docs)))
                 (range 10))))))

(deftest test-create-analyzer
    (testing "test if Lucene analyzers are created correctly."
      (is (= (class (create-analyzer))
             org.apache.lucene.analysis.standard.StandardAnalyzer))))

(deftest test-create-directory
    (testing "test if Lucene directories are created correctly."
      (is (= (class (create-directory :RAM))
             org.apache.lucene.store.RAMDirectory))
      (let [directory (create-directory "/tmp/test")]
        (is (= (class directory) org.apache.lucene.store.NIOFSDirectory))
        (is (or 
              (= (str (.getFile directory)) "/tmp/test")
              (= (str (.getFile directory)) "/private/tmp/test"))))))

(deftest test-create-index-reader
    (testing "test if Lucene IndexReaders are created correctly."
      (let [dir (create-directory :RAM)]
        ;; write to index to avoid no segments file error
        (do (add-documents-to-index! dir dummy-docs))
        (is (= (class (create-index-reader dir)) 
               org.apache.lucene.index.ReadOnlyDirectoryReader)))))

(deftest test-create-field
  (testing "test if create-field properly creates a Lucene Field"
    (let [field (create-field "foo" "bar" :stored :analyzed)]
      (is (= (.name field) "foo"))
      (is (= (.stringValue field) "bar"))
      (is (.isIndexed field))
      (is (.isStored field))
      (is (.isTokenized field)))
      
    (let [field (create-field "foo" "bar" :stored)]
      (is (= (.name field) "foo"))
      (is (= (.stringValue field) "bar"))
      (is (not (.isIndexed field)))
      (is (.isStored field))
      (is (not (.isTokenized field))))

    (let [field (create-field "foo" "bar" :indexed-not-analyzed)]
      (is (= (.name field) "foo"))
      (is (= (.stringValue field) "bar"))
      (is (.isIndexed field))
      (is (not (.isStored field)))
      (is (not (.isTokenized field))))
          
    (let [field (create-field "foo" "bar" :analyzed)]
      (is (= (.name field) "foo"))
      (is (= (.stringValue field) "bar"))
      (is (.isIndexed field))
      (is (not (.isStored field)))
      (is (.isTokenized field)))

    ;; fields that are neither indexed nor stored don't make sense:
    (is (thrown? Exception (create-field "foo" "bar")))))

(deftest test-create-numeric-field
  (let [indexed-field (create-numeric-field "answer" 42 :indexed)]
    (is (.isIndexed indexed-field))
    (is (not (.isStored indexed-field))))

  (let [stored-field (create-numeric-field "answer" 42 :stored)]
    (is (not (.isIndexed stored-field)))
    (is (.isStored stored-field)))
  
  (let [stored-and-indexed-field (create-numeric-field "answer"
                                                       42
                                                       :stored
                                                       :indexed)]
    (is (.isIndexed stored-and-indexed-field))
    (is (.isStored stored-and-indexed-field)))
  
  (let [double-field (create-numeric-field "answer" 42.0 :stored)]
    (is (= (.name double-field) "answer"))
    (is (= (.getNumericValue double-field) 42.0))
    (is (= (class (.getNumericValue double-field)) java.lang.Double))
    (is (not (.isIndexed double-field)))
    (is (.isStored double-field))
    (is (not (.isTokenized double-field))))
  
  (let [float-field (create-numeric-field "answer" (float 42.0))]
    (is (= (.getNumericValue float-field) (float 42.0)))
    (is (= (class (.getNumericValue float-field)) java.lang.Float)))

  (let [int-field (create-numeric-field "answer" (int 42))]
    (is (= (.getNumericValue int-field) (int 42)))
    (is (= (class (.getNumericValue int-field)) java.lang.Integer)))

  (let [long-field (create-numeric-field "timestamp" (long 1320398212253))]
    (is (= (.getNumericValue long-field) 1320398212253))
    (is (= (class (.getNumericValue long-field)) java.lang.Long)))

  (let [nil-field (create-numeric-field "nil" nil)]
    (is (= (.getNumericValue nil-field) -1))
    (is (= (class (.getNumericValue nil-field)) java.lang.Integer))))

(deftest test-distill-plaintext
  (is (= (distill-plaintext (str "<div><p>Hello, World!</p>"
                                 "<img src=\"/images/globe.png\" "
                                 "alt=\"The Globe\" "
                                 "title=\"The Globe\">"
                                 "</div>"))
         (str "Hello, World!\n"
              "The Globe")))
  
  (is (= (distill-plaintext
          (str
           "<p>0</p>"
           "<p>1</p>"
           "<p>2</p>"
           "<p>3</p>"
           "<p>4</p>"
           "<p>5</p>"
           "<p>6</p>"
           "<p>7</p>"
           "<p>8</p>"
           "<img src=\"nine.png\" title=\"9\"/>"))
         (str
          "0\n"
          "1\n"
          "2\n"
          "3\n"
          "4\n"
          "5\n"
          "6\n"
          "7\n"
          "8\n"
          "9"))))

(deftest test-create-document
  (testing "Check if the document is correctly tranlated to a Lucene doc"
    (let [document (create-document (first dummy-docs))]
      (is (= (class document) org.apache.lucene.document.Document))

      (let [field (.getField document "fulltext")]
        (is (= (.name field) "fulltext"))
        (is (.isIndexed field))
        (is (not (.isStored field)))
        (is (.isTokenized field))
        (is (= (.stringValue field) "bar\nffff\nme small")))
      
      (let [field (.getField document "title")]
        (is (= (.name field) "title"))
        (is (.isIndexed field))
        (is (.isStored field))
        (is (.isTokenized field))
        (is (= (.stringValue field) "bar")))

      (let [field (.getField document "language")]
        (is (= (.name field) "language"))
        (is (.isIndexed field))
        (is (.isStored field))
        (is (not (.isTokenized field)))
        (is (= (.stringValue field) "en")))
      
      (let [field (.getFieldable document "published")]
        (is (= (.name field) "published"))
        (is (.isIndexed field))
        (is (.isStored field))
        (is (= (.stringValue field) "1320398212253")))
      
      (let [field (.getFieldable document "updated")]
        (is (= (.name field) "updated"))
        (is (.isIndexed field))
        (is (.isStored field))
        (is (= (.stringValue field) "1320408934590")))

      (let [field (.getField document "feed")]
        (is (= (.name field) "feed"))
        (is (.isIndexed field))
        (is (.isStored field))
        (is (not (.isTokenized field)))
        (is (= (.stringValue field) "blog")))
      
      (let [field (.getField document "slug")]
        (is (= (.name field) "slug"))
        (is (.isIndexed field))
        (is (.isStored field))
        (is (not (.isTokenized field)))
        (is (= (.stringValue field) "/2011/11/4/bar"))))))

(deftest test-document-to-map
  (is (= (document-to-map (create-document (first dummy-docs)))
         {:slug "/2011/11/4/bar"
          :feed "blog"
          :title "bar"
          :language "en"
          :published (time-coerce/from-long 1320398212253)
          :updated (time-coerce/from-long 1320408934590)}))

  (is (= (document-to-map (create-document (second dummy-docs)))
         {:slug "/2012/1/15/hic-sunt-dracones"
          :feed "blog"
          :title "Hic sunt dracones"
          :language "en"
          :published (time-coerce/from-long 1326638932253)
          :updated nil})))

(deftest test-create-index-writer
  (testing "test if index writers are created correctly"
    (let [directory (create-directory :RAM)
          analyzer (create-analyzer)
          writer (create-index-writer analyzer directory :create)
          config (.getConfig writer)]
      
      (is (= (class writer) IndexWriter))
      (is (= (.getAnalyzer writer) analyzer))
      (is (= (.getDirectory writer) directory))

      (is (= (.getRAMBufferSizeMB config) 49.0))

      (is (= (.getOpenMode config) IndexWriterConfig$OpenMode/CREATE))

      (close-index-writer writer)
      
      (let [writer (create-index-writer analyzer directory :append)
            config (.getConfig writer)]
        (is (= (.getOpenMode config) IndexWriterConfig$OpenMode/APPEND))
        (close-index-writer writer))
      
      (let [writer (create-index-writer analyzer directory :create-or-append)
            config (.getConfig writer)]
        (is (= (.getOpenMode config)
               IndexWriterConfig$OpenMode/CREATE_OR_APPEND))
        (close-index-writer writer)))))

(deftest test-close-index-writer
  (testing "test if index writers are really closed"
  
    (let [writer (create-index-writer (create-analyzer)
                                      (create-directory :RAM)
                                      :create)]
      (is (not (get-field IndexWriter "closed" writer)))
      (is (get-field IndexWriter "closed" (close-index-writer writer))))))

(deftest test-add-documents-to-index!
  (testing "test passing all documents at once to an encapsulated writer"
    (let [directory (create-directory :RAM)]
      (do
        (add-documents-to-index! directory dummy-docs-extended))
      
      (let [reader (create-index-reader directory)]
        (is (= (map #(.get % "title")
                    (get-docs reader (map #(ScoreDoc. % 1.0) (range 14))))
               ["bar"
                "Hic sunt dracones"
                "Brora!"
                "Caol Ila"
                "Caol Ila 0"
                "Caol Ila 1"
                "Caol Ila 2"
                "Caol Ila 3"
                "Caol Ila 4"
                "Caol Ila 5"
                "Caol Ila 6"
                "Caol Ila 7"
                "Caol Ila 8"
                "Caol Ila 9"])))))

  (testing "test passing documents in batches to the same writer"
    (let [directory (create-directory :RAM)
          writer (create-index-writer (create-analyzer) directory :create)]
      (do
        (add-documents-to-index! writer
                                 (subvec (vec dummy-docs-extended) 0 2))
        (add-documents-to-index! writer
                                 (subvec (vec dummy-docs-extended) 2 4))
        (add-documents-to-index! writer
                                 (subvec (vec dummy-docs-extended) 4 6))
        (add-documents-to-index! writer
                                 (subvec (vec dummy-docs-extended) 6 8))
        (add-documents-to-index! writer
                                 (subvec (vec dummy-docs-extended) 8 10))
        (add-documents-to-index! writer
                                 (subvec (vec dummy-docs-extended) 10 12))
        (add-documents-to-index! writer
                                 (subvec (vec dummy-docs-extended) 12 14))

        (close-index-writer writer))
      
      (let [reader (create-index-reader directory)]
        (is (= (map #(.get % "title")
                    (get-docs reader (map #(ScoreDoc. % 1.0) (range 14))))
               ["bar"
                "Hic sunt dracones"
                "Brora!"
                "Caol Ila"
                "Caol Ila 0"
                "Caol Ila 1"
                "Caol Ila 2"
                "Caol Ila 3"
                "Caol Ila 4"
                "Caol Ila 5"
                "Caol Ila 6"
                "Caol Ila 7"
                "Caol Ila 8"
                "Caol Ila 9"]))))))

(deftest test-delete-document-from-index!
  (testing "test if document is deleted correctly."
    (let [directory (create-directory :RAM)
          analyzer (create-analyzer)]
      (do
        (add-documents-to-index! directory dummy-docs-extended))

      (let [reader (create-index-reader directory)
            filter (create-filter {:slug "/2011/11/4/bar"})
            result (search "me" filter 15 reader analyzer)
            docs (get-docs reader (:docs result))]
        (is (= (:total-hits result) 1))
        (is (= (.get (first docs) "title") "bar")))
        
      (do
        (delete-document-from-index! directory "/2011/11/4/bar"))

      (let [reader (create-index-reader directory)
            filter (create-filter {:slug "/2011/11/4/bar"})
            result (search "me" filter 15 reader analyzer)
            docs (get-docs reader (:docs result))]
        (is (= (:total-hits result) 0))))))

(deftest test-update-document-in-index!
  (testing "test if document is updated correctly."
    (let [directory (create-directory :RAM)
          analyzer (create-analyzer)]
      (do
        (add-documents-to-index! directory dummy-docs-extended))

      (let [reader (create-index-reader directory)
            filter (create-filter {:slug "/2011/11/4/bar"})
            result (search "me" filter 15 reader analyzer)
            docs (get-docs reader (:docs result))]
        (is (= (:total-hits result) 1))
        (is (= (.get (first docs) "title") "bar")))
        
      (do
        (update-document-in-index! directory
                                   "/2011/11/4/bar"
                                   (assoc (first dummy-docs-extended)
                                     :title "baz")))

      (let [reader (create-index-reader directory)
            filter (create-filter {:slug "/2011/11/4/bar"})
            result (search "me" filter 15 reader analyzer)
            docs (get-docs reader (:docs result))]
        (is (= (:total-hits result) 1))
        (is (= (.get (first docs) "title") "baz"))))))

(deftest test-create-date-range-query
  (let [query (create-date-range-query "42"
                                       "1985-08-04T09:00:00.0Z"
                                       "2012-01-15T17:54:45.0Z")]
    (is (= (class query)
           org.apache.lucene.search.NumericRangeQuery))

    (is (= (.getMin query)
           (datetime-string-to-long "1985-08-04T09:00:00.0Z")))
    (is (= (.getMax query)
           (datetime-string-to-long "2012-01-15T17:54:45.0Z"))))

  (is (thrown? Exception (create-date-range-query "error" "foo" "bar"))))

(deftest test-create-filter
  (testing "test if filter with empty arguments returns nil"
    (is (nil? (create-filter {}))))

  (testing "test creating a filter with a single range query"
    ;; N.B. if the filter is nil the bq retrieval through get-field
    ;; results in a NullPointerException in UnsafeFieldAccessorImpl.java
    (let [published-filter (create-filter {:published-between
                                           {:min "2011-08-04T09:00:00.0Z"
                                            :max "2012-08-04T09:00:00.0Z"}})
          updated-filter (create-filter {:updated-between
                                         {:min "2011-08-04T09:00:00.0Z"
                                          :max "2012-08-04T09:00:00.0Z"}})
          published-bq (get-field org.apache.lucene.search.QueryWrapperFilter
                                  "query"
                                  published-filter)
          updated-bq (get-field org.apache.lucene.search.QueryWrapperFilter
                                "query"
                                updated-filter)
          published-query (.getQuery (first (.getClauses published-bq)))
          updated-query (.getQuery (first (.getClauses updated-bq)))]

      (are [filter]
           (= (class filter) org.apache.lucene.search.QueryWrapperFilter)
           published-filter
           updated-filter)
                      
      (are [query]
           (= (class query) org.apache.lucene.search.NumericRangeQuery)
           published-query
           updated-query)

      (are [query]
           (and
            (= (.getMin query)
               (datetime-string-to-long "2011-08-04T09:00:00.0Z"))
            (= (.getMax query)
               (datetime-string-to-long "2012-08-04T09:00:00.0Z")))
           published-query
           updated-query)))

  (testing "test with a single text-based filter"
    (let [language-filter (create-filter {:language "en"})
          feed-filter (create-filter {:feed "blog"})
          language-bq (get-field org.apache.lucene.search.QueryWrapperFilter
                                 "query"
                                 language-filter)
          feed-bq (get-field org.apache.lucene.search.QueryWrapperFilter
                             "query"
                             feed-filter)
          language-query (.getQuery (first (.getClauses language-bq)))
          feed-query (.getQuery (first (.getClauses feed-bq)))
          language-term (.getTerm language-query)
          feed-term (.getTerm feed-query)]

      (are [filter]
           (= (class filter) org.apache.lucene.search.QueryWrapperFilter)
           language-filter
           feed-filter)

      (are [query]
           (= (class query) org.apache.lucene.search.TermQuery)
           language-query
           feed-query)

      (are [term field-name]
           (= (.field term) field-name)
           language-term "language"
           feed-term "feed")
      
      (are [term value]
           (= (.text term) value)
           language-term "en"
           feed-term "blog")))

  (testing "test a composite filter with all filters included"
    (let [filter (create-filter {:published-between
                                 {:min "2011-08-04T09:00:00.0Z"
                                  :max "2012-08-04T09:00:00.0Z"}
                                 :updated-between
                                 {:min "2011-01-01T09:00:00.0Z"
                                  :max "2012-01-01T09:00:00.0Z"}
                                 :language "en"
                                 :feed "blog"
                                 :slug "/2011/11/4/bar"})
          bq (get-field org.apache.lucene.search.QueryWrapperFilter
                        "query"
                        filter)
          published-query (.getQuery (first (.getClauses bq)))
          updated-query (.getQuery (second (.getClauses bq)))
          language-query (.getQuery (nth (.getClauses bq) 2))
          feed-query (.getQuery (nth (.getClauses bq) 3))
          slug-query (.getQuery (last (.getClauses bq)))
          language-term (.getTerm language-query)
          feed-term (.getTerm feed-query)
          slug-term (.getTerm slug-query)]

      (is (= (class filter)
             org.apache.lucene.search.QueryWrapperFilter))

      (are [query klass]
           (= (class query) klass)
           published-query org.apache.lucene.search.NumericRangeQuery
           updated-query org.apache.lucene.search.NumericRangeQuery
           language-query org.apache.lucene.search.TermQuery
           feed-query org.apache.lucene.search.TermQuery
           slug-query org.apache.lucene.search.TermQuery)

      (are [query min]
           (= (.getMin query) (datetime-string-to-long min))
           published-query "2011-08-04T09:00:00.0Z"
           updated-query "2011-01-01T09:00:00.0Z")

      (are [term field-name]
           (= (.field term) field-name)
           language-term "language"
           feed-term "feed"
           slug-term "slug")
      
      (are [query max]
           (= (.getMax query) (datetime-string-to-long max))
           published-query "2012-08-04T09:00:00.0Z"
           updated-query "2012-01-01T09:00:00.0Z")

      (are [term value]
           (= (.text term) value)
           language-term "en"
           feed-term "blog"
           slug-term "/2011/11/4/bar"))))

(deftest test-search
  (let [dir (create-directory :RAM)
        analyzer (create-analyzer)
        writer (create-index-writer analyzer dir :create)]
    (do
      (add-documents-to-index! writer dummy-docs-extended)
      (close-index-writer writer))

    (let [reader (create-index-reader dir)]

      ;; the limit argument needs to be an integer
      (is (thrown? Exception (search "whisky" nil nil reader analyzer)))
      
      (testing "test a simple query without filters"
        (let [result (search "whisky" nil 15 reader analyzer)
              docs (get-docs reader (:docs result))]

          (is (= (:total-hits result) 12))

          (are [doc title]
               (= (.get doc "title") title)
               (first docs) "Brora!"
               (nth docs 1) "Caol Ila"
               (nth docs 2) "Caol Ila 0"
               (nth docs 3) "Caol Ila 1"
               (nth docs 4) "Caol Ila 2"
               (nth docs 5) "Caol Ila 3"
               (nth docs 6) "Caol Ila 4"
               (nth docs 7) "Caol Ila 5"
               (nth docs 8) "Caol Ila 6"
               (nth docs 9) "Caol Ila 7"
               (nth docs 10) "Caol Ila 8"
               (nth docs 11)  "Caol Ila 9")))

      (testing "test pagination"
        (let [first-page-result (search "whisky" nil 5 reader analyzer)
              first-page-docs (get-docs reader (:docs first-page-result))]

          (are [doc title]
               (= (.get doc "title") title)
               (first first-page-docs) "Brora!"
               (nth first-page-docs 1) "Caol Ila"
               (nth first-page-docs 2) "Caol Ila 0"
               (nth first-page-docs 3) "Caol Ila 1"
               (last first-page-docs) "Caol Ila 2")

          (is (= (:last-doc first-page-result)
                 {:id 6 :score (float 0.18987726)}))
          
          (let [second-page-result (search "whisky"
                                           nil
                                           5
                                           (:id
                                            (:last-doc first-page-result))
                                           (:score
                                            (:last-doc first-page-result))
                                           reader
                                           analyzer)
                second-page-docs (get-docs reader
                                           (:docs second-page-result))]

            (are [doc title]
                 (= (.get doc "title") title)
                 (first second-page-docs) "Caol Ila 3"
                 (nth second-page-docs 1) "Caol Ila 4"
                 (nth second-page-docs 2) "Caol Ila 5"
                 (nth second-page-docs 3) "Caol Ila 6"
                 (last second-page-docs) "Caol Ila 7")

            (let [third-page-result (search "whisky"
                                            nil
                                            5
                                            (:id
                                             (:last-doc second-page-result))
                                            (:score
                                             (:last-doc second-page-result))
                                            reader
                                            analyzer)
                  third-page-docs (get-docs reader
                                            (:docs third-page-result))]

              (are [doc title]
                   (= (.get doc "title") title)
                   (first third-page-docs) "Caol Ila 8"
                   (last third-page-docs) "Caol Ila 9")))))

      (testing "test with a feed-based filter"
        (let [filter (create-filter {:feed "blog"})
              result (search "whisky" filter 15 reader analyzer)
              docs (get-docs reader (:docs result))]
          (is (= (:total-hits result) 1))
          (is (= (.get (first docs) "title") "Brora!"))))

      (testing "test with a slug-based filter"
        (let [filter (create-filter {:slug "/2011/11/4/bar"})
              result (search "me" filter 15 reader analyzer)
              docs (get-docs reader (:docs result))]
          (is (= (:total-hits result) 1))
          (is (= (.get (first docs) "title") "bar"))))

      (testing "test with a language-based filter"
        (let [filter (create-filter {:language "en"})
              result (search "whisky" filter 15 reader analyzer)
              docs (get-docs reader (:docs result))]
          (is (= (:total-hits result) 12)))

        (let [filter (create-filter {:language "nl"})
              result (search "whisky" filter 15 reader analyzer)
              docs (get-docs reader (:docs result))]
          (is (= (:total-hits result) 0))))

      (testing "test with a time-based filter"
        (let [filter (create-filter {:published-between
                                     {:min
                                      "2012-01-16T11:00:00.0Z"
                                      :max
                                      "2012-01-16T12:00:00.0Z"}})
              result (search "whisky" filter 15 reader analyzer)
              docs (get-docs reader (:docs result))]
          (is (= (:total-hits result) 1))
          (is (= (.get (first docs) "title") "Brora!")))

        (let [filter (create-filter {:published-between
                                     {:min "2012-01-16T11:00:00.0Z"
                                      :max "2012-01-16T12:00:00.0Z"}
                                     :updated-between
                                     {:min
                                      "2012-01-16T11:00:00.0Z"
                                      :max
                                      "2012-01-16T12:00:00.0Z"}})
              result (search "whisky" filter 15 reader analyzer)]
          (is (= (:total-hits result) 0)))))))

(deftest test-search-jump-to-page
  (let [dir (create-directory :RAM)
        analyzer (create-analyzer)
        writer (create-index-writer analyzer dir :create)]
    (do
      (add-documents-to-index! writer dummy-docs-extended)
      (close-index-writer writer))

    (let [reader (create-index-reader dir)]
      
      (testing "test what happens when the first page is requested"
        (let [result (search-jump-to-page "whisky" nil 5 1 reader analyzer)
              docs (get-docs reader (:docs result))]

          (are [doc title]
               (= (.get doc "title") title)
               (first docs) "Brora!"
               (nth docs 1) "Caol Ila"
               (nth docs 2) "Caol Ila 0"
               (nth docs 3) "Caol Ila 1"
               (last docs) "Caol Ila 2")

          (is (= (:last-doc result)
                 {:id 6 :score (float 0.18987726)})))
    
        (testing "test what happens when the second page is requested"
          (let [result (search-jump-to-page "whisky" nil 5 2 reader analyzer)
                docs (get-docs reader (:docs result))]

            (are [doc title]
                 (= (.get doc "title") title)
                 (first docs) "Caol Ila 3"
                 (nth docs 1) "Caol Ila 4"
                 (nth docs 2) "Caol Ila 5"
                 (nth docs 3) "Caol Ila 6"
                 (last docs) "Caol Ila 7")))
    
        (testing "test what happens when the third page is requested"
          (let [result (search-jump-to-page "whisky" nil 5 3 reader analyzer)
                docs (get-docs reader (:docs result))]

            (are [doc title]
                 (= (.get doc "title") title)
                 (first docs) "Caol Ila 8"
                 (last docs) "Caol Ila 9")))

        (testing "test what happens when requesting an imaginary fourth page"
          (is (= (search-jump-to-page "whisky" nil 5 4 reader analyzer)
                 nil)))))))

(deftest test-get-doc
  (let [dir (create-directory :RAM)]
    (do (add-documents-to-index! dir dummy-docs))
    (let [reader (create-index-reader dir)]
      (is (= (.get (get-doc reader 0) "title") "bar")))))

(deftest test-get-docs
  (let [dir (create-directory :RAM)]
    (do (add-documents-to-index! dir dummy-docs))
    (let [reader (create-index-reader dir)]
      (is (= (map #(.get % "title")
                  (get-docs reader [(ScoreDoc. 1 1.0)
                                    (ScoreDoc. 2 1.0)
                                    (ScoreDoc. 3 1.0)]))
             ["Hic sunt dracones" "Brora!" "Caol Ila"])))))