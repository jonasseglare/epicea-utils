(ns bluebell.utils.wip.access-test
  (:refer-clojure :exclude [get set update remove update])
  (:require [clojure.test :refer :all]
            [bluebell.utils.wip.access :refer :all]
            [bluebell.utils.wip.optional :refer [optional]]))

(def k (key-accessor :k {:valid-value? int? :default-value 0}))

(deftest basic-map-access
  (is (= 3 ((:get k) {:k 3})))
  (is (= {:k 9} ((:set k) {} 9)))
  (is ((:has? k) {:k 3}))
  (is (not ((:has? k) {:p 3}))))

(deftest base-validator
  (is (thrown? Throwable ((:validate-base k) [])))
  (is (= {:r 4} ((:validate-base k) {:r 4}))))

(deftest validate-has-test
  (is (thrown? Throwable ((:validate-has k) {:r 9})))
  (is (= {:k 111} ((:validate-has k) {:k 111}))))

(deftest validate-value-test
  (is (thrown? Throwable ((:validate-value k) "asfdasdf")))
  (is ((:validate-value k) 9)))

(deftest get-optional-test
  (is (= [3] ((:get-optional k) {:k 3})))
  (is (nil? ((:get-optional k) {:r 3})))
  (is (thrown? Throwable ((:get-optional k) [])))
  (is (thrown? Throwable ((:get-optional k) {:k :a}))))

(deftest checked-get-test
  (is (= 3 ((:checked-get k) {:k 3})))
  (is (thrown? Throwable ((:checked-get k) {:k :a}))))

(deftest checked-set-test
  (is (= {:k 9} ((:checked-set k) {:k 3} 9))))

(deftest update-test
  (is (= {:k 10} ((:update k) {:k 9} inc))))

(deftest prepare-test
  (is (= {:k 0} ((:prepare k) {})))
  (is (= {:k 9} ((:prepare k) {:k 9}))))

(deftest get-or-default-test
  (is (= 9 ((:get-or-default k) {:k 9})))
  (is (= 0 ((:get-or-default k) {}))))

;;;;;;;;;;;;;;;;;;;;;;;
(def v (index-accessor 1))

(deftest vector-tests
  (is (= 119 ((:checked-get v) [3 119])))
  (is (= [nil 9] ((:checked-set v) [nil nil] 9)))
  (is (not ((:has? v) []))))

(def a (key-accessor :a))
(def b (key-accessor :b))
(def c (key-accessor :c))

(defn abc-add [x]
  ((:checked-set c)
   x (+ ((:checked-get a) x)
        ((:checked-get b) x))))

(deftest abc-test
  (is (= {:a 3 :b 4 :c 7} (abc-add {:a 3 :b 4}))))

(deftest remove-test
  (is (= {:a 3} ((:checked-remove b) {:a 3 :b 4}))))

(deftest composite-map-tests
  (is (= [[:a 3]] (get-required {:a 3 :b [4]})))
  (is (= [[:b 4]] (get-optional {:a 3 :b [4]}))))

(def ur (map-accessor {:username (key-accessor :username)
                       :email [(key-accessor :email)]} {}))

(deftest map-can-get-test
  (is ((:has? ur) {:username "Mjao"}))
  (is (not ((:has? ur) {:masdf "masdf"})))
  (is (= {:username "Kalle"}
         ((:get ur) {:a 9 :b 3 :username "Kalle"})))
  (is (= {:username "Kalle" :email "mjao"}
         ((:get ur) {:a 9 :b 3 :username "Kalle" :email "mjao"}))))

(def ag (key-accessor :a))
(def bg (key-accessor :b))

(def ab (serial ag bg))

(deftest serial-test
  (is (= 3 ((:get ab) {:a {:b 3}})))
  (is (= {:a {:b 4}}
         ((:set ab) {:a {:b 3}} 4)))
  (is (not ((:has? ab) {:a {}})))
  (is ((:has? ab) {:a {:b 3}}))
  (is (= ((:remove ab) {:a {:b 3}})
         {:a {}}))
  (is (= [3] ((:get-optional ab) {:a {:b 3}}))))
