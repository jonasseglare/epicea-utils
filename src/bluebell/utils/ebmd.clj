(ns bluebell.utils.ebmd
  "Example-based overloading"
  (:require [clojure.spec.alpha :as spec]
            [bluebell.utils.wip.specutils :as specutils]
            [clojure.core :as c]
            [bluebell.utils.wip.pareto :as pareto]
            [bluebell.utils.wip.core :as utils]
            [bluebell.utils.wip.check :refer [check-io]]
            [clojure.set :as cljset]
            [bluebell.utils.render-text :as render-text]))

(declare filter-positive)
(declare any-arg)
(declare check-valid-arg-spec)
(declare render-overload-text)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;  Specs
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(spec/def ::pos coll?)
(spec/def ::neg coll?)
(spec/def ::pred fn?)
(spec/def ::key any?)
(spec/def ::desc string?)
(spec/def ::spec #(or (spec/spec? %)
                        (keyword? %)))
(spec/def ::valid? boolean?)

(spec/def ::arg-spec (spec/keys :req-un [::pred
                                         ::pos
                                         ::neg
                                         ::key
                                         ::valid?
                                         ::desc]
                                
                                :opt-un [::spec]))

(spec/def ::input-arg-spec
  (spec/keys :opt-un [::pred ::pos ::neg
                      ::key ::desc ::spec]))



(spec/def ::arg-specs (spec/coll-of ::arg-spec))
(spec/def ::fn fn?)
(spec/def ::joint ::arg-spec)
(spec/def ::overload (spec/keys :req-un [::arg-specs ::fn]
                                :opt-un [::joint]))

(spec/def ::arg-binding (spec/cat :arg-spec any?
                                  :binding any?))

(spec/def ::joint-binding (spec/cat :prefix #{:joint}
                                    :arg-spec any?))

(spec/def ::def-poly-arg-list
  (spec/* (spec/alt :joint ::joint-binding
                    :arg-binding ::arg-binding)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;  Impl
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;; Warning: Make sure that the arg specs don't differentiate
;;; between vectors and seqs, because internally, we store the
;;; samples in a set, meaning that vectors and seqs will be
;;; collapsed to a single element, which is possibly a seq.
;;; In short, use sequential? and avoid
;;; vector? or seq? to test args.

(defn- decorate-key-and-pred-from-spec [input-arg-spec]
  (if-let [s (:spec input-arg-spec)]
    (merge {:key [::spec s]
            :pred (specutils/pred s)} input-arg-spec)
    input-arg-spec))

(defn- validate-on-samples [arg-spec]
  (let [pred (:pred arg-spec)
        pos-failures (filter (complement pred)
                             (:pos arg-spec))
        neg-failures (filter pred (:neg arg-spec))]
    (if (and (empty? pos-failures)
               (empty? neg-failures))
      (assoc arg-spec :valid? true)
      (merge arg-spec
             {:valid? false
              :pos-failures pos-failures
              :neg-failures neg-failures}))))

(defn- decorate-desc [arg-spec]
  {:pre [(contains? arg-spec :key)]}
  (merge {:desc (str (:key arg-spec))} arg-spec))


(defn- init-overload-state [overload-sym init-samples]
  (check-io
   [:pre [(symbol? overload-sym)]]
   
   {:name overload-sym
    :init-samples init-samples
    :dirty? true
    :samples init-samples
    :arg-specs {}
    :overloads {}}))

(defn- state-arg-specs [state]
  (-> state
      :arg-specs
      vals
      set))

(defn- mark-dirty [x]
  (assoc x :dirty? true))

(defn- unmark-dirty [x]
  (assoc x :dirty? false))

(defn- add-arg-spec [state arg-spec]
  (check-io
   [:pre [(map? state)
          ::arg-spec arg-spec]]
   
   (-> state
       mark-dirty
       (update :samples (partial reduce into) [(:pos arg-spec)
                                               (:neg arg-spec)])
       (assoc-in [:arg-specs (:key arg-spec)] arg-spec))))

(defn- add-arg-specs [state arg-specs]
  (reduce add-arg-spec state arg-specs))

(defn- extend-arg-specs-with-joint [overload]
  (conj (vec (:arg-specs overload))
        (or (:joint overload)
            any-arg)))

(defn- add-overload [state overload]
  (check-io
   [:pre [(map? state)
          ::overload overload]]
   (let [arg-specs (extend-arg-specs-with-joint overload)]
     (-> state
         mark-dirty
         (add-arg-specs arg-specs)
         (assoc-in [:overloads (count arg-specs)
                    (mapv :key arg-specs)]
                   overload)))))

(defn- rebuild-arg-spec-samples [state]
  (let [samples (:samples state)]
    (update state
            :arg-specs
            (fn [arg-specs]
              (into {}
                    (map (fn [[k v]]
                           [k (assoc
                               v :samples
                               (set
                                (filter-positive v samples)))])
                         arg-specs))))))

(defn- cart-prod [a b]
  (transduce
   (comp (map (fn [a] (mapv (fn [b] [a b]) b)))
         cat)
   conj
   []
   a))

(defn- compare-sets [a b]
  (cond
    (= a b) :equal
    (cljset/subset? a b) :subset
    (cljset/superset? a b) :superset
    :default :disjoint))

(defn- rebuild-arg-spec-comparisons [state]
  (let [arg-specs (:arg-specs state)]
    (assert (map? arg-specs))
    (assoc
     state
     :arg-spec-comparisons
     (into {}
           (map (fn [[[ak av] [bk bv]]]
                  (assert (contains? av :samples))
                  (assert (contains? bv :samples))
                  [[ak bk] (compare-sets (:samples av)
                                         (:samples bv))])
                (cart-prod arg-specs arg-specs))))))

(defn- get-all-overload-arg-lists [state]
  (transduce
   (comp (map (fn [[arity overloads-per-arity]]
                {:pre [(number? arity)
                       (map? overloads-per-arity)]}
                (mapv first overloads-per-arity)))
         cat)
   conj
   []
   (:overloads state)))

(defn- compare-arg-lists [state a b]
  (if (= (count a) (count b))
    (let [cmps (:arg-spec-comparisons state)
          pairs (set (map (comp (partial get cmps) vector) a b))]
      (and (contains? pairs :subset)
             (not (contains? pairs :superset))))
    false))

(defn- compute-overload-dominates? [state]
  (let [arg-lists (get-all-overload-arg-lists state)]
    (transduce
     (map (fn [[arg-list-a arg-list-b]]
            [[arg-list-a arg-list-b]
             (compare-arg-lists
              state
              arg-list-a
              arg-list-b)]))
     conj
     {}
     (cart-prod arg-lists arg-lists))))

(defn- rebuild-overload-dominates? [state]
  (assoc
   state
   :overload-dominates?
   (compute-overload-dominates? state)))

(defn- rebuild-all [state]
  (-> state
      rebuild-arg-spec-samples
      rebuild-arg-spec-comparisons
      rebuild-overload-dominates?
      unmark-dirty))

(defn- dominates? [lookup-table
                   [a-args a-fn]
                   [b-args b-fn]]
  {:pre [(spec/valid? ::overload a-fn)
         (spec/valid? ::overload b-fn)]}
  (let [value (get lookup-table [a-args b-args])]
    (assert (boolean? value))
    value))

(defn- matches? [arg-specs arg-list args]
  {:pre [(= (count arg-list) (count args))]}
  (every? identity
          (map (fn [arg-spec-key arg]
                 (let [arg-spec (get arg-specs arg-spec-key)]
                   ((:pred arg-spec) arg))) arg-list args)))

(defn- list-pareto-elements [state overloads args]
  (let [arg-specs (:arg-specs state)]
    (pareto/elements
     (transduce
      (filter (fn [[arg-list f]] (matches?
                                  arg-specs
                                  arg-list
                                  args)))
      (completing pareto/insert)
      (pareto/frontier (partial dominates?
                                (:overload-dominates? state)))
      overloads))))

(defn- render-candidate [c]
  (render-overload-text c))

(defn- render-ambiguous-overload-message [name candidates]
  (render-text/evaluate
   (render-text/add-line "Ambiguous overload for '" name "'")
   (render-text/break 1)
   (for [c candidates]
     (render-candidate c))))

(defn- resolve-overload-for-arity [state overloads args]
  (let [e (list-pareto-elements state overloads args)]
    (cond
      (= 0 (count e)) (throw (ex-info "No overload found"
                                      {:name (:name state)
                                       :arity (dec (count args))
                                       :args (butlast args)}))
      (< 1 (count e)) (throw (ex-info
                              (render-ambiguous-overload-message
                               (:name state) e)
                              {}))
      :default (first e))))

(defn- resolve-overload [state args]
  {:pre [(not (:dirty? state))]}
  (let [arity (count args)
        overloads (:overloads state)]
    (if-let [m (get overloads arity)]
      (resolve-overload-for-arity state m args)
      (throw (ex-info "No overload for this arity"
                      {:symbol (:name state)
                       :arity (dec arity)})))))

(defn- extend-args-with-joint [args0]
  (let [args (vec args0)]
    (conj args args)))

(defn- evaluate-overload [state args]
  (let [[arg-spec-keys overload] (resolve-overload
                                  state (extend-args-with-joint args))]
    (apply (:fn overload) args)))

(defn- perform-special-op [state-atom args]
  (let [f (first args)]
    (case f
      ::add-overload (do (swap! state-atom
                                add-overload
                                (second args))
                         true)
      ::get-state (deref state-atom)
      ::get-state-atom state-atom
      false)))

(defn- perform-evaluation [state-atom args]
  (let [state (deref state-atom)
        state (if (:dirty? state)
                (swap! state-atom rebuild-all)
                state)]
    (evaluate-overload state args)))

(def common-samples #{[] {} #{} "asdf" nil 9 :a 'a identity})

(defn make-overload-fn
  ([sym] (make-overload-fn sym common-samples))
  ([sym initial-samples]
   (let [state-atom (atom (init-overload-state
                           sym
                           initial-samples))]
     (fn [& args]
       (or (perform-special-op state-atom args)
           (perform-evaluation state-atom args))))))

(defn- reset-state [state]
  (init-overload-state (:name state)
                       (:init-samples state)))

(defn- render-overload-text [[signature overload]]
  [(render-text/add-line "Overload:")
   (render-text/indent
    (render-text/pprint (vec (butlast signature)))
    (let [l (last signature)]
      (if (= l (:key any-arg))
        []
        [(render-text/add-line "Joint:")
         (render-text/pprint l)])))])

(defn- render-arity-text [[full-arity overloads]]
  [(render-text/add-line "Arity " (dec full-arity))
   (render-text/indent
    (render-text/add-line "Number of overloads " (count overloads))
    (render-text/break 1)
    (mapv
     render-overload-text
     overloads))])

(defn- render-comparison-columns [arg-specs]
  [(render-text/add-line "")
   (if (empty? arg-specs)
     (render-text/add-line "Samples")
     (let [arg-spec (first arg-specs)]
       [(render-text/add-line (str (:key arg-spec)))
        (render-text/with-indent-step
          "| "
          (render-text/indent
           (render-comparison-columns (rest arg-specs))))]))])

(defn- render-comparison-arrows [n]
  (render-text/add-line
   (take n (repeat "v "))))

(defn- render-sample-evaluation [sample arg-specs]
  (let [evals (map (fn [arg-spec]
                     (if ((:pred arg-spec) sample)
                       "1 "
                       ". "))
                   arg-specs)]
    (render-text/add-line evals (str sample))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;  Interface
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;------- Arg spec -------
(defn normalize-arg-spec [input-arg-spec]
  {:pre [(spec/valid? ::input-arg-spec input-arg-spec)]
   :post [(spec/valid? ::arg-spec %)]}
  (-> (merge {:pos [] :neg []} input-arg-spec)
      decorate-key-and-pred-from-spec
      decorate-desc
      validate-on-samples))


(defn normalize-and-check-arg-spec [x]
  (check-valid-arg-spec (normalize-arg-spec x)))

(defmacro def-arg-spec [sym value]
  {:pre [(symbol? sym)]}
  `(def ~sym
     (check-valid-arg-spec
      (normalize-arg-spec
       (merge {:key [::def-arg-spec
                     ~(keyword (str *ns*) (name sym))]}
              ~value)))))

(defn provide-samples [arg-spec samples]
  (check-io
   [:pre [(map? arg-spec)
          (contains? arg-spec :pred)]]
   (let [pred (:pred arg-spec)]
     (-> arg-spec
         (update :pos
                 (fn [pos]
                   (into
                    (set pos)
                    (filter pred samples))))
         (update :neg
                 (fn [neg]
                   (into
                    (set neg)
                    (filter (complement pred) samples))))))))

(defn arg-spec-samples [arg-spec]
  (reduce into [] [(:pos arg-spec)
                   (:neg arg-spec)]))

(defn pred [pred-fn]
  "Easy construction of an arg-spec. This should only be used for very common values, because it just uses a default set of sample values"
  {:pre [(fn? pred-fn)]}
  (provide-samples
   {:pred pred-fn}
   common-samples))

(def arg-spec? (specutils/pred ::arg-spec))

(defn filter-positive [arg-spec samples]
  (check-io
   [:pre [::arg-spec arg-spec
          (coll? samples)]
    :post k [(coll? k)]]
   (filter (:pred arg-spec) samples)))

(defn matches-arg-spec? [arg-spec x]
  (check-io
   [:pre [::arg-spec arg-spec]]
   ((:pred arg-spec) x)))

;;;------- Overload -------
(defmacro declare-poly
  ([sym initial-samples]
   `(defonce ~sym (make-overload-fn (quote ~sym)
                                    ~initial-samples)))
  ([sym]
   `(defonce ~sym (make-overload-fn (quote ~sym)))))

(defmacro def-poly [sym arg-list & body]
  {:pre [(symbol? sym)]}
  (let [p (spec/conform ::def-poly-arg-list arg-list)
        all-parsed-args (utils/categorize-tuples p)
        function-args (:arg-binding all-parsed-args)]
    (if (= p ::spec/invalid)
      (throw (ex-info
              "Bad def-poly arg list"
              {}))
      `(~sym ::add-overload
        (merge
         {:arg-specs ~(mapv :arg-spec function-args)
          :fn (fn [~@(mapv :binding function-args)]
                ~@body)}
         ~(if-let [j (-> all-parsed-args :joint first :arg-spec)]
            {:joint j}
            {}))))))

;; Extra helper functions

(defn samples [overload-fn]
  {:pre [(fn? overload-fn)]}
  (:samples (overload-fn ::get-state)))

(defn arities [overload-fn]
  {:pre [(fn? overload-fn)]}
  (->> ::get-state
       overload-fn
       :overloads
       keys
       (map dec)
       set))

(defn poly-state [polyed-fn]
  {:pre [(fn? polyed-fn)]}
  (polyed-fn ::get-state))

(defn reset-poly! [overload-fn]
  {:pre [(fn? overload-fn)]}
  (swap! (overload-fn ::get-state-atom) reset-state))

(defn print-poly-str [poly-fn]
  {:pre [(fn? poly-fn)]}
  (let [state (poly-state poly-fn)]
    (render-text/evaluate
     (render-text/add-line "Name: " (:name state))
     (render-text/break 1)
     (render-text/add-line "Samples:")
     (render-text/indent
      (render-text/pprint (:samples state)))
     (render-text/break 1)
     (render-text/add-line "Arg specs:")
     (render-text/indent
      (render-text/pprint (-> state
                              :arg-specs
                              keys)))
     (render-text/break 1)
     (render-text/add-line "Overloads")
     (render-text/indent
      (mapv
       render-arity-text
       (:overloads state))))))

(def print-poly (comp println print-poly-str))

(defn print-arg-spec-comparison-str [samples arg-specs]
  (check-io
   [:pre [::arg-specs arg-specs]]
   (render-text/evaluate
    (render-comparison-columns arg-specs)
    (render-comparison-arrows (inc (count arg-specs)))
    (for [sample samples]
      (render-sample-evaluation sample arg-specs)))))

(def print-arg-spec-comparison (comp
                                println
                                print-arg-spec-comparison-str))

(defn print-poly-arg-spec-comparison
  [poly]
  {:pre [(fn? poly)]}
  (print-arg-spec-comparison
   (samples poly)
   (-> (poly-state poly)
       state-arg-specs)))

;;;------- Misc -------
(defn check-valid-arg-spec [arg-spec]
  {:pre [(spec/valid? ::arg-spec arg-spec)]}
  (when (not (:valid? arg-spec))
    (throw (ex-info
            (str "Invalid arg-spec '" (:desc arg-spec) "'")
            arg-spec)))
  (if (empty? (:pos arg-spec))
    (println "Warning: No posiive samples for "
             (:key arg-spec)))
  arg-spec)

(def-arg-spec any-arg (pred any?))

