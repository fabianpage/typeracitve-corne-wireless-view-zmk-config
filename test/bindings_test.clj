(ns bindings-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [bindings :as bindings]))

;; --- Keyword dispatch keys -----------------------------------------------

(deftest keyword-generic-compiles-to-kp
  (is (= "&kp P" (bindings/compile-binding :P)))
  (is (= "&kp ESCAPE" (bindings/compile-binding :ESCAPE)))
  (is (= "&kp DE_S" (bindings/compile-binding :DE_S))))

(deftest keyword-trans-compiles-to-trans
  (is (= "&trans" (bindings/compile-binding :trans))))

(deftest keyword-none-compiles-to-none
  (is (= "&none" (bindings/compile-binding :none))))

(deftest keyword-param-op-compiles-to-macro-param
  (is (= "&macro_param_1to1" (bindings/compile-binding :param-1to1)))
  (is (= "&macro_param_1to2" (bindings/compile-binding :param-1to2)))
  (is (= "&macro_param_2to1" (bindings/compile-binding :param-2to1)))
  (is (= "&macro_param_2to2" (bindings/compile-binding :param-2to2))))

;; --- Vector dispatch keys -----------------------------------------------

(deftest vector-generic-compiles-with-op-and-args
  (is (= "&lt 3 DE_S" (bindings/compile-binding [:lt 3 :DE_S])))
  (is (= "&bt BT_SEL 0" (bindings/compile-binding [:bt :BT_SEL 0])))
  (is (= "&mo 2" (bindings/compile-binding [:mo 2])))
  (is (= "&to 4" (bindings/compile-binding [:to 4]))))

(deftest vector-generic-with-no-args
  (is (= "&sk" (bindings/compile-binding [:sk]))))

(deftest vector-macro-press-compiles-with-recursive-inner
  (is (= "&macro_press &kp A" (bindings/compile-binding [:press :A])))
  (is (= "&macro_press &mo 2" (bindings/compile-binding [:press [:mo 2]])))
  (is (= "&macro_press &lt 3 DE_S" (bindings/compile-binding [:press [:lt 3 :DE_S]]))))

(deftest vector-macro-release-compiles-with-recursive-inner
  (is (= "&macro_release &kp B" (bindings/compile-binding [:release :B])))
  (is (= "&macro_release &mo 2" (bindings/compile-binding [:release [:mo 2]]))))

(deftest vector-macro-tap-compiles-with-recursive-inner
  (is (= "&macro_tap &kp C" (bindings/compile-binding [:tap :C])))
  (is (= "&macro_tap &bt BT_SEL 0" (bindings/compile-binding [:tap [:bt :BT_SEL 0]]))))

(deftest vector-wait-compiles-to-macro-wait-time
  (is (= "&macro_wait_time 30" (bindings/compile-binding [:wait 30])))
  (is (= "&macro_wait_time 100" (bindings/compile-binding [:wait 100]))))

(deftest vector-tap-time-compiles-to-macro-tap-time
  (is (= "&macro_tap_time 50" (bindings/compile-binding [:tap-time 50])))
  (is (= "&macro_tap_time 20" (bindings/compile-binding [:tap-time 20]))))

(deftest vector-pause-compiles-to-macro-pause
  (is (= "&macro_pause_for_release" (bindings/compile-binding [:pause]))))

(deftest vector-param-op-compiles-to-macro-param
  (is (= "&macro_param_1to1" (bindings/compile-binding [:param-1to1 :_placeholder])))
  (is (= "&macro_param_2to1" (bindings/compile-binding [:param-2to1 :_placeholder])))
  (is (= "&macro_param_1to2" (bindings/compile-binding [:param-1to2 :_placeholder]))))

;; --- Primitive dispatch key ---------------------------------------------

(deftest primitive-numbers-compile-to-string
  (is (= "0" (bindings/compile-binding 0)))
  (is (= "42" (bindings/compile-binding 42))))

(deftest primitive-strings-compile-to-string
  (is (= "foo" (bindings/compile-binding "foo"))))

;; --- Deep recursion tests -----------------------------------------------

(deftest deeply-nested-wrappers-compile-recursively
  (is (= "&macro_press &macro_tap &kp A"
         (bindings/compile-binding [:press [:tap :A]]))))

;; --- Edge cases ---------------------------------------------------------

(deftest nil-compiles-as-empty-string
  (is (= "" (bindings/compile-binding nil))))

;; --- Test runner --------------------------------------------------------

(defn run
  []
  (let [{:keys [fail error] :as result} (run-tests 'bindings-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Tests failed" result)))
    result))

(comment
  (run)
  )
