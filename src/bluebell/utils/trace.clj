(ns bluebell.utils.trace
  (:require [clojure.spec.alpha :as spec]
            [bluebell.utils.core :as utils]
            [bluebell.utils.specutils :as sutils]))

(defn trace-fn []
  (let [state (atom [])]
    (fn
      ([]
       (deref state))
      ([value]
       (swap! state (fn [state] (conj state [value (System/nanoTime)])))))))

(defn record [dst data]
  (when dst
    (dst data))
  data)

(defn begin [dst tag]
  (record dst [:begin tag]))

(defn end [dst tag]
  (record dst [:end tag]))

;[[:a 11639427761547] [[:begin :b] 11639427772743] [[:begin :c] 11639427778198] [:kattskit 11639427782285] [[:end :c] 11639427788288] [[:end :b] 11639427791153] [:d 11639427795402]]

;;;;;


(defn time-pair [value-spec]
  (spec/spec (spec/cat :value value-spec
                       :time ::nanoseconds)))

(spec/def ::single-item (time-pair any?))

(spec/def ::nanoseconds number?)

(defn pair [tag-spec value-spec]
  (spec/spec (spec/cat :tag tag-spec
                       :value value-spec)))

(defn kw-vec [kw]
  (pair #{kw} any?))

(spec/def ::begin (time-pair (kw-vec :begin)))
(spec/def ::end (time-pair (kw-vec :end)))
(spec/def ::block (spec/cat :begin (spec/spec ::begin)
                            :trace ::trace
                            :end (spec/spec ::end)))

(spec/def ::trace-part (spec/alt :block ::block
                                 :item ::single-item))

(spec/def ::trace (spec/* ::trace-part))

(defn consistent-block? [block]
  (= (-> block
         :begin
         :value
         :value)
     (-> block
         :end
         :value
         :value)))

(defn list-invalid-blocks
  ([trace-data] (list-invalid-blocks [] trace-data))
  ([dst trace-data]
   (transduce
    (comp (map (fn [[item-type item-data]]
                 (if (= item-type :block)
                   item-data)))
          (filter identity)
          (map (fn [block]
                 (list-invalid-blocks
                  (if (consistent-block? block)
                    []
                    [block])
                  (:trace block))))
          cat)
    conj
    dst
    trace-data)))

(defn parse-spec [trace-data]
  (sutils/force-conform ::trace trace-data))

(defn format-block-bound [bound]
  {:time (:time bound)
   :value (-> bound :value :value)})

(defn format-invalid-block [block]
  {:begin (format-block-bound (:begin block))
   :end (format-block-bound (:end block))})

(defn check-no-invalid-blocks [parse-spec]
  (let [invalid (list-invalid-blocks parse-spec)]
    (if (not (empty? invalid))
      (throw (ex-info "Invalid blocks"
                      {:blocks (map format-invalid-block invalid)})))
    parse-spec))

(defn parse [trace-data]
  (-> trace-data
      parse-spec
      check-no-invalid-blocks))

(defn disp-parsed [])

(defn disp-trace [trace]
  (if (fn? trace)
    (disp-trace (trace))
    (-> trace
        parse
        (disp-parsed ""))))