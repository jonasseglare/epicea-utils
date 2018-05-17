(ns bluebell.utils.symset
  (:require [clojure.spec.alpha :as spec]
            [bluebell.utils.core :as utils]
            [bluebell.utils.specutils :as specutils]
            )
  (:refer-clojure :exclude [complement any?]))

;; Type of operations:
;;   - Is an element part of a set?
;;   - A set can be a subset of another set
;;   - An element belongs to a number of sets

(spec/def ::set-id clojure.core/any?)
(spec/def ::element-id clojure.core/any?)
(spec/def ::set-ids (spec/and (spec/coll-of ::set-id)
                              set?))
(spec/def ::supersets ::set-ids)
(spec/def ::set-entry (spec/keys :req-un [::supersets]))
(spec/def ::set-map (spec/map-of ::set-id ::set-entry))
(spec/def ::element-entry (spec/keys ::req-un [::set-ids]))
(spec/def ::element-map (spec/map-of ::element-id ::element-entry))
(spec/def ::generator fn?)
(spec/def ::generator-id keyword?)
(spec/def ::generators (spec/map-of ::generator-id ::generator))
(spec/def ::registry (spec/keys :req-un [::set-map ::element-map ::generators]))

(def empty-element-entry {:set-ids #{}})

(def empty-set-entry {:supersets #{}})

(def empty-set-registry {:set-map {}
                         :element-map {}
                         :generators {}})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;  Implementation
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-supersets [set-registry set-id]
  (-> set-registry
      :set-map
      :supersets))

(defn initialize-element [set-registry element]
  (update-in set-registry [:element-map element] #(or % empty-element-entry)))

(defn register-membership [set-registry element set-id]
  (-> set-registry
      (update-in [:element-map element :set-ids] #(conj % set-id))))


(defn register-superset [set-registry set-id-a set-id-b]
  (update-in set-registry [:set-map set-id-a :supersets] #(conj % set-id-b)))

(declare member-of?)

(defn normalize-query [X]
  (cond
    (fn? X) X
    (set? X) (fn [set-registry element]
               (contains? X element))
    :default (fn [set-registry element]
               (member-of? set-registry element X))))

(defn generate-supersets-for-set [set-registry s]
  (transduce
   (map (fn [[k g]]
          (specutils/validate set? (or (g s) #{}))))
   clojure.set/union
   #{}
   (:generators set-registry)))



(declare subset-of)

(defn generate-supersets [set-registry0 seen-sets0 unseen-sets0]
  (loop [set-registry set-registry0
         seen-sets seen-sets0
         unseen-sets unseen-sets0]
    (assert (set? seen-sets))
    (assert (set? unseen-sets))
    (if (empty? unseen-sets)
      set-registry
      (let [f (first unseen-sets)
            supersets-of-first (generate-supersets-for-set set-registry f)]
        (assert (set? supersets-of-first))
        (recur
         (reduce (fn [reg sup] (subset-of reg f sup)) set-registry supersets-of-first)
         (conj seen-sets f)
         (set (clojure.set/difference
               (clojure.set/union (set (rest unseen-sets))
                                       supersets-of-first)
               seen-sets)))))))

(declare all-sets)

(defn initialize-set [set-registry set-id]
  (let [new-registry (update-in set-registry [:set-map set-id] #(or % empty-set-entry))]
    (if (= new-registry set-registry)
      set-registry
      (generate-supersets new-registry
                          (all-sets set-registry)
                          #{set-id}))))

(defn add-superset-generator-sub [set-registry key generator]
  (update set-registry :generators #(assoc % key generator)))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;  High level API
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn set-registry? [x]
  (spec/valid? ::registry x))

(def add-element initialize-element)

(def add-set initialize-set)



(defn member-of [set-registry element set-id]
  (-> set-registry
      (initialize-element element)
      (initialize-set set-id)
      (register-membership element set-id)))

(defn add
  "Adds it as both a set and as an element, the element belonging to its own set."
  [set-registry x]
  (member-of set-registry x x))

(defn subset-of [set-registry set-id-a set-id-b]
  (-> set-registry
      (initialize-set set-id-a)
      (initialize-set set-id-b)
      (register-superset set-id-a set-id-b)))

(defn supersets-of [set-registry set-ids]
  (assert (or (coll? set-ids)
              (nil? set-ids)))
  (transduce
   (comp (map (fn [set-id]
                (supersets-of
                 set-registry
                 (get-in set-registry [:set-map set-id :supersets]))))
         cat)
   conj
   set-ids
   set-ids))

(defn set-memberships [set-registry element]
  (set (supersets-of
        set-registry
        (get-in set-registry [:element-map element :set-ids]))))

(defn member-of? [set-registry element set-id]
  (contains? (set-memberships set-registry element) set-id))

(defn all-sets [set-registry]
  (-> set-registry
      :set-map
      keys
      set))

(defn all-elements [set-registry]
  (-> set-registry
      :element-map
      keys
      set))

(defn element? [set-registry element]
  (contains? (:element-map set-registry) element))

(defn add-superset-generator [set-registry key generator]
  (-> set-registry
      (add-superset-generator-sub key generator)
      (generate-supersets #{} (set (all-sets set-registry)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;  Query API
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn any? [sym-registry x]
  true)

(def universe element?)

(defn union [& args]
  (fn [set-registry element]
    (some (fn [f] (f set-registry element))
          (map normalize-query args))))

(defn intersection [& args]
  (fn [set-registry element]
    (every? (fn [f] (f set-registry element))
            (map normalize-query args))))

(defn complement [x]
  (-> x
      normalize-query
      clojure.core/complement))

(defn difference [a b]
  (fn [set-registry element]
    (and ((normalize-query a) set-registry element)
         (not ((normalize-query b) set-registry element)))))


(defn evaluate-query [set-registry query]
  (let [f (normalize-query query)]
    (set
     (filter
      #(f set-registry %)
      (-> set-registry
          :element-map
          keys)))))

(defn satisfies-query? [set-registry query x]
  (let [f (normalize-query query)]
    (f set-registry x)))




(comment
  (do
    
    (def a (member-of empty-set-registry :x :numbers))
    (def b (member-of a :x :elements))
    (def c (subset-of b :rationals :numbers))
    (def d (member-of c :y :rationals))

    (println ":numbers in d" (evaluate-query d :numbers))
    (println ":rationals in d" (evaluate-query d :rationals))
    (evaluate-query d (complement :rationals))


    (def e (member-of d :z :elements))

    (println ":numbers in e" (evaluate-query e :numbers))
    (println ":rationals in e" (evaluate-query e :rationals))
    (evaluate-query e (complement :numbers))
    (evaluate-query e (union :numbers :elements))
    (evaluate-query e (intersection :numbers :elements))

    (evaluate-query e (difference :numbers :elements))

    (evaluate-query e #{:kattskit})

    )

  )
