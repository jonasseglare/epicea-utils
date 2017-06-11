(ns nettle.utils.core
  (:require [clojure.set]))
            

(defn flatten-map-hierarchy [mh]
  (reduce
   into {}
   (map 
    (fn [k m]
      (map (fn [[k2 v]] [[k k2] v])
           (vec m)))
    (keys mh)
    (vals mh))))


(defn map-map [f m]
  (into {} (map f m)))

(defn map-vals [f m]
  (map-map (fn [[k v]] [k (f v)]) m))

(defn map-keys [f m]
  (map-map (fn [[k v]] [(f k) v]) m))

(defn map-map-with-state [f state m] ;; f: state x item -> [new-state new-item]
  (reduce 
   (fn [[state m] kv-pair]
     (let [[new-state new-item] (f state kv-pair)]
       [new-state (conj m new-item)]))
   [state {}]
   m))

(defn map-vals-with-state [f state m]
  (map-map-with-state
   (fn [state [k v]]
     (let [[new-state new-value] (f state v)]
       [new-state [k new-value]]))
   state m))
   

(defn map-with-keys? [x ks]
  (if (map? x)
    (= ks (clojure.set/intersection (set (keys x)) ks))))

(defn compute-matrix-index [sizes indices]
  (assert (= (count sizes)
             (count indices)))
  (loop [S (vec sizes)
         I (vec indices)
         i 0]
    (if (empty? I)
      i
      (recur (pop S) (pop I) (+ (* i (last S)) (last I))))))
      
;; Macro to define an initial value and the keys
(defmacro def-map [map-name empty-map]
  `(do
     (def ~(symbol (str "empty-" map-name)) ~empty-map)
     (defn ~(symbol (str map-name "?")) [x#]
       (map-with-keys? x# ~(set (keys empty-map))))))





(defn default-arg? [x]
  (and (vector? x) (= 1 (count x))))

(defn valid-partial-args? [x]
  (if (sequential? x)
    (every? (fn [y] (or (nil? y)
                        (default-arg? y)))
            x)))

(defn merge-args [default-args0 args0]
  (loop [result []
        defargs default-args0
        args args0]
    (if (empty? defargs)
      (reduce conj result args)
      (if (default-arg? (first defargs))
        (recur (conj result (ffirst defargs))
               (rest defargs)
               args)
        (recur (conj result (first args))
               (rest defargs)
               (rest args))))))

(defn tuple-generator [n]
  (let [m (- n 1)]
    (fn [[acc p] x]
      (if (= m (count p))
        [(conj acc (conj p x)) []]
        [acc (conj p x)]))))

(defn form-tuples [tuple-size data]
  (first
   (reduce 
    (tuple-generator tuple-size)
    [[] []]
    data)))

(defn form-pairs [data]
  (form-tuples 2 data))

;; EXAMPLE: (def f (provide-arguments get [[{:a 1 :b 2 :c 3}] nil]))
;; EXAMPLE: (def f (provide-arguments get [nil [:a]]))
(defn provide-arguments [fun partial-args]
  (assert (valid-partial-args? partial-args))
  (fn [& args0]
    (apply fun (merge-args partial-args args0))))

(defmacro pipe [& args]
  (loop [result (first args)
         functions (rest args)]
    (if (empty? functions)
      result
      (recur `(~(first functions) ~result)
             (rest functions)))))
      
(defn common-error [& args]  
  (throw (RuntimeException. (apply str args))))

(defn bundle [n]
  (let [g (atom [])]
    (fn [r]
      (fn [dst x]
        (let [next (swap! g conj x)]
          (if (= (count next) n)
            (do (reset! g [])
                (r dst next))
            dst))))))

(defn or-nil [fun]
  (fn [& args]
    (try
      (apply fun args)
      (catch Throwable _ nil))))

;; For generating code in-place
(defmacro macro-eval [code]
  (eval code))