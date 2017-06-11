(ns nettle.fibers.types
  (:require [nettle.fibers.core :refer :all]
            [clojure.spec :as spec]
            [nettle.utils.access :as access]
            [nettle.utils.core :as utils]))

(def primitive-traits {:primitive? true})
(def primitive-number-traits (merge 
                              primitive-traits
                              {:scalar? true}))

(def unchecked-int-ops {:add-op 'clojure.core/unchecked-add-int
                        :mul-op 'clojure.core/unchecked-multiply-int
                        :sub-op 'clojure.core/unchecked-subtract-int
                        :negate-op 'clojure.core/unchecked-negate-int
                        :inc-op 'clojure.core/unchecked-inc-int
                        :dec-op 'clojure.core/unchecked-dec-int})

(def unchecked-float-ops {:add-op 'clojure.core/unchecked-add
                          :mul-op 'clojure.core/unchecked-multiply
                          :sub-op 'clojure.core/unchecked-subtract
                          :negate-op 'clojure.core/unchecked-negate
                          :inc-op 'clojure.core/unchecked-inc
                          :dec-op 'clojure.core/unchecked-dec})

(def primitive-list [[[:bool] (merge 
                               primitive-traits 
                               {:construct boolean})]
                     [[:int32 :int] (merge 
                                     primitive-number-traits 
                                     unchecked-int-ops
                                     {:construct 'clojure.core/unchecked-int})]
                     [[:int64 :long] (merge 
                                      primitive-number-traits
                                      unchecked-int-ops
                                      {:construct 'clojure.core/unchecked-long})]
                     [[:float :float32] (merge 
                                         primitive-number-traits
                                         unchecked-float-ops
                                         {:construct 'clojure.core/unchecked-float})]
                     [[:float64 :double] (merge
                                          primitive-number-traits
                                          unchecked-float-ops
                                          {:construct 'clojure.core/unchecked-double})]])

(defn scalar? [x]
  (:scalar? x))

(defn primitive? [x]
  (:primitive? x))

(defn add-primitive-entry [dst [names properties]]
  (reduce
   (fn [dst name]
     (assoc 
      dst name 
      (assoc properties :priority 
             (count dst))))
   dst
   names))

(def primitives (reduce add-primitive-entry {} primitive-list))

(defn priority [x]
  (get-in primitives [(datatype x) :priority]))

(defn choose-higher-priority [a b]
  (if (< (priority a) (priority b))
    b a))

(defn common-datatype [args]
  (access/get
   (reduce choose-higher-priority args)
   -datatype))
;; (defn homogenize [args]
;;   (let [dt (common-datatype args)]
;;     {:type dt
;;      :args (map (-> primitive dt :construct)
;;                 args)}))

(defn primitive-type? [x]
  (contains? primitives x))

(defn with-traits [t x]
  (if-let [y (get primitives t)]
    (merge x y)
    (utils/common-error "Not a primitive: " t)))

(defn primitive
  ([t] (assert (primitive-type? t))
   (with-traits t (primitive-expr t)))
  ([t x] 
   (assert (primitive-type? t))
   (with-traits t (primitive-expr t x))))

(spec/def ::scalar-map (spec/map-of (constantly true) scalar?))

(defn ad-sub [x derivative-map]
  (assert (scalar? x))
  (assert (spec/valid? ::scalar-map derivative-map))
  {:type :ad :x x :derivatives derivative-map})

(defn ad 
  ([x] (ad x {}))
  ([x derivative-map] (ad-sub x derivative-map)))

(defn is-type? 
  ([x t]
   (if (map? x)
     (= t (:type x))))
  ([t] #(is-type? % t)))

(def ad? (is-type? :ad))

