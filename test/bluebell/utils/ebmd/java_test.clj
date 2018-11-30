(ns bluebell.utils.ebmd.java-test
  (:import [bluebell.utils.ebmd Registry ArgSpec
            IndirectArgSpec EmptyArgSpec
            PromotionPath
            Promotion
            CachedDominates
            PairKey
            ArgSpecDominates
            Impl
            Signature
            Settings
            PolyFn]
           [java.util HashSet]
           [bluebell.utils IDominates])
  (:require [bluebell.utils.ebmd.java :refer :all]
            [clojure.set :as cljset]
            [clojure.test :refer :all]))

(def debug-settings (.debug (Settings.)))

(deftest this-is-good
  (let [reg (Registry. debug-settings)
        num-spec (ArgSpec. number?
                           #{3 4 9 3.4}
                           #{:a "asdf"})
        kwd-spec (ArgSpec. keyword?
                           #{:a :b}
                           #{9 3})
        str-spec (ArgSpec. string?
                           #{"asdf"}
                           #{})]

    (.registerPromotion reg
                        :keyword
                        (Promotion. keyword)
                        :string)

    (.registerArgSpec reg :keyword kwd-spec)

    (is (= 1 (count (.getPromotions reg :keyword))))

    (is (thrown? Exception (.resolve reg :mjao)))

    (.registerArgSpec reg :mjao num-spec)

    (is (= 0 (count (.getPromotions reg :mjao))))
    
    (.registerIndirection reg :kattskit :mjao)

    ;(is (instance? EmptyArgSpec (.getOrMakeArgSpecAtKey reg :asdfasdfs)))

    ;(is (instance? IndirectArgSpec (.getOrMakeArgSpecAtKey reg :kattskit)))
    
    (is (identical? num-spec
                    (.resolve reg :kattskit)))
    (let [res (.resolve reg :mjao)]
      (is (identical? res num-spec))
      (is (.evaluate num-spec 9.7))
      (is (not (.evaluate num-spec :kattskit))))))

(deftest shortest-promotion-path
  (let [reg (Registry. debug-settings)
        double-spec (ArgSpec. double? #{3.4} #{})
        float-spec (ArgSpec. float? #{(float 3.4)} #{})
        int-spec (ArgSpec. int? #{(int 3)} #{})
        str-spec (ArgSpec. string? #{"asdfsdf"} #{})]
    (.registerArgSpec reg :double double-spec)
    (.registerArgSpec reg :float float-spec)
    (.registerArgSpec reg :int int-spec)
    (.registerArgSpec reg :string str-spec)

    (.registerPromotion reg
                        :string
                        (Promotion. str)
                        :double)
    (.registerPromotion reg
                        :double
                        (Promotion. double)
                        :float)
    (.registerPromotion reg
                        :float
                        (Promotion. float)
                        :int)
    (.registerPromotion reg
                        :double
                        (Promotion. double)
                        :int)


    (let [paths (.listPromotionPaths reg :string)]
      (is (= 5 (count paths)))
      (let [p (PromotionPath/findBestMatch paths 9)]
        (is (not (nil? p)))
        (is (.matches p 9))
        (is (not (.matches p "9")))
        (is (= "9.0" (.promote p true 9))))
      (is (nil? (PromotionPath/findBestMatch paths :katt))))

    (is (thrown? RuntimeException
                 (.getPromotionPaths reg :string)))
    (is (true? (.rebuildIfNeeded reg)))
    (is (false? (.rebuildIfNeeded reg)))
    (is (not (nil? (.getPromotionPaths reg :string))))))


(defn as-from-set [s]
  {:pre [(set? s)]}
  (ArgSpec.
   (fn [x] (contains? s x))
   s
   (cljset/difference (set (range 10)) s)))

(deftest domination-test
  (let [a (as-from-set #{0 1})
        b (as-from-set #{0})
        c (as-from-set #{1})]
    (is (.dominatesOnSamples b #{0 1} a))
    (is (.dominatesOnSamples b #{1} a))
    (is (not (.dominatesOnSamples b #{0} a)))
    (is (not (.dominatesOnSamples a #{0 1} b)))

    (is (not (.dominatesOnSamples b #{0 1} c)))
    (is (not (.dominatesOnSamples c #{0 1} b)))
    (is (.dominatesOnSamples c #{0} b))
    (is (.dominatesOnSamples b #{1} c))))

(deftest arg-spec-dom-test
  (let [reg (Registry. debug-settings)
        dom (ArgSpecDominates. reg #{0 1})
        samples (HashSet.)]
    (.registerArgSpec reg :same (ArgSpec. (fn [[a b & r]]
                                            (= a b))
                                          #{[0 0] [1 1]}
                                          #{[0 1] [1 0]}))
    (.registerArgSpec reg :a (as-from-set #{0 1}))
    (.registerArgSpec reg :b (as-from-set #{0}))
    (.registerArgSpec reg :c (as-from-set #{1}))
    (.rebuild reg)

    (is (.dominates dom :b :a))
    (is (.dominates dom :c :a))
    (is (not (.dominates dom :c :b)))
    (is (not (.dominates dom :b :c)))
    (is (not (.dominates dom :a :b)))
    (is (not (.dominates dom :a :c)))

    (is (.dominates dom :b :a))
    (is (.dominates dom :c :a))
    (is (not (.dominates dom :c :b)))
    (is (not (.dominates dom :b :c)))
    (is (not (.dominates dom :a :b)))
    (is (not (.dominates dom :a :c)))

    (let [ab (Signature. (object-array [:a :b]) nil)
          abs (Signature. (object-array [:a :b]) :same)]
      (.accumulateSamples ab reg samples)
      (is (= (count samples) 10))
      (.rebuild ab reg)
      (.rebuild abs reg)
      (is (nil? (.evaluatePromotionPaths
                 ab
                 (object-array [9 10]))))
      (is (= 2 (count (.evaluatePromotionPaths
                       ab
                       (object-array [0 0])))))
      (is (= 2 (count (.evaluatePromotionPaths
                       ab
                       (object-array [1 0])))))
      (is (= 2 (count (.evaluatePromotionPaths
                       abs
                       (object-array [0 0])))))
      (is (nil? (.evaluatePromotionPaths
                 abs
                 (object-array [1 0]))))
      (is (nil? (.evaluatePromotionPaths
                 ab
                 (object-array [1 9])))))

    (let [s (Signature. (object-array [:a :a]) nil)
          impl (Impl. s +)]
      (.rebuild s reg)
      (let [args (object-array [0 0])
            impl (.evaluatePromotionPaths
                  impl
                  args)]
        (is (not (nil? impl)))
        (is (= 0 (.evaluate impl true args)))))

    (is (= (Signature. (object-array [:a :b]) nil)
           (Signature. (object-array [:a :b]) nil)))
    
    (is (= (Signature. (object-array [:a :b]) identity)
           (Signature. (object-array [:a :b]) identity)))
    
    (is (not (= (Signature. (object-array [:a :b]) identity)
                (Signature. (object-array [:a :b]) -))))
    
    (is (not (= (Signature. (object-array [:a :b]) nil)
                (Signature. (object-array [:a]) nil))))
    
    (is (not (= (Signature. (object-array [:a :b]) nil)
                (Signature. (object-array [:a]) nil))))
    
    (is (not (= (Signature. (object-array [:a :b]) nil)
                (Signature. (object-array [:a :c]) nil))))

    (is (= #{(Signature. (object-array [:a :b]) nil)}
           (conj #{(Signature. (object-array [:a :b]) nil)}
                 (Signature. (object-array [:a :b]) nil))))
    
    (is (not= #{(Signature. (object-array [:a :b]) nil)}
              (conj #{(Signature. (object-array [:a :b]) nil)}
                    (Signature. (object-array [:a :b]) +))))

    (is (.dominates (Signature. (object-array [:b :a]) nil)
                    dom
                    (Signature. (object-array [:a :a]) nil)))
    
    (is (not (.dominates (Signature. (object-array [:a :a]) nil)
                         dom
                         (Signature. (object-array [:b :a]) nil))))
    
    (is (not (.dominates (Signature. (object-array [:b :a]) nil)
                         dom
                         (Signature. (object-array [:b :a]) nil))))

    (is (.dominates dom :a nil))

    (is (.dominates (Signature. (object-array [:b :a]) :numbers)
                    dom
                    (Signature. (object-array [:b :a]) nil)))
    
    (is (not (.dominates (Signature. (object-array [:b :a])
                                     nil)
                         dom
                         (Signature. (object-array [:b :a])
                                     identity))))))

(deftest pair-key-test
  (let [k0 (PairKey. 0 1)
        k1 (PairKey. 0 1)]
    (is (= k0 k1))
    (is (= (.hashCode k0)
           (.hashCode k1)))))

(deftest cached-dominates
  (let [cd (CachedDominates. (proxy [IDominates] []
                               (dominates [a b]
                                 (< a b))))]
    (is (= 0 (.getEvalCounter cd)))
    (is (.dominates cd 0 1))
    (is (= 1 (.getEvalCounter cd)))
    (is (.dominates cd 0 1))
    (is (= 1 (.getEvalCounter cd)))
    (is (not (.dominates cd 1 0)))
    (is (= 2 (.getEvalCounter cd)))
    (is (not (.dominates cd 1 0)))
    (is (= 2 (.getEvalCounter cd)))))

(deftest basic-poly-fns
  (let [reg (Registry. debug-settings)
        poly (PolyFn. reg :add)
        int-spec (ArgSpec. int? #{(int 3)} #{})
        str-spec (ArgSpec. string? #{"asdfsdf"} #{})
        any-spec (ArgSpec. any? #{true false 9 :asdf "asd"} #{})
        ordered-int-pair (ArgSpec.
                          (fn [x]
                            (and (sequential? x)
                                 (= 2 (count x))
                                 (let [[a b] x]
                                   (<= a b))))
                          #{[1 2] [3 4] [1 1]}
                          #{[3 2] 9 :a })]
    (.registerArgSpec reg :int int-spec)
    (.registerArgSpec reg :string str-spec)
    (.registerArgSpec reg :any any-spec)
    (.registerArgSpec reg :ordered-int-pair ordered-int-pair)
    (.addImplementation poly (Impl. (Signature.
                                     (into-array
                                      Object
                                      [:int :int])
                                     :any)
                                    (fn [a b] (+ a b))))
    (is (= 3 (.call poly (object-array [1 2]))))
    (is (= 5 (.call poly (object-array [1 4]))))

    (.addImplementation poly (Impl. (Signature.
                                     (into-array
                                      Object
                                      [:string :string]) 
                                      :any)
                                    (fn [a b]
                                      (str a b))))


    (is (= 3 (.call poly (object-array [1 2]))))
    (is (= "kattsk1t" (.call poly (object-array
                                   ["katt" "sk1t"]))))
    (is (thrown? Exception
                 (.call poly (object-array
                              [1 "asdf"]))))
    (.registerPromotion
     reg

     :string
     (Promotion. str)
     :int)

    (is (= 3 (.call poly (object-array [1 2]))))
    (is (= "1asdf" (.call poly (object-array [1 "asdf"]))))
    (is (= "asdf119" (.call poly (object-array ["asdf" 119]))))
    (is (= "ab" (.call poly (object-array ["a" "b"]))))
    (.addImplementation poly (Impl. (Signature.
                                     (into-array
                                      Object
                                      [:int :int])
                                     :ordered-int-pair)
                                    (fn [a b] [:ordered
                                               (+ a b)])))
    (is (= [:ordered 3] (.call poly (object-array [1 2]))))
    (is (= 3 (.call poly (object-array [2 1]))))))
