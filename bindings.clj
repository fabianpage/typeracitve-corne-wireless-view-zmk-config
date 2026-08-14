(ns bindings
  (:require [clojure.string :as str]))

(defn- token->str
  "Convert a token to its string representation for ZMK output."
  [token]
  (if (keyword? token)
    (name token)
    (str token)))

(defn dispatch-key
  "Classify a binding cell into a finest-grain dispatch key."
  [cell]
  (cond
    (vector? cell)
    (let [op (first cell)]
      (case op
        (:press :release :tap) :vector/macro-press
        :wait                   :vector/wait
        :tap-time               :vector/tap-time
        :pause                  :vector/pause
        (:param-1to1 :param-1to2 :param-2to1 :param-2to2)
                                :vector/param-op
                                :vector/generic))

    (keyword? cell)
    (case cell
      :trans                  :keyword/trans
      :none                   :keyword/none
      (:param-1to1 :param-1to2 :param-2to1 :param-2to2)
                              :keyword/param-op
                              :keyword/generic)

    :else :primitive))

(def ^:private compilers
  "Registry of compiler adapter functions from dispatch keys to ZMK strings.
   Each adapter has signature (cell compile) where compile is the recursive
   dispatcher reference."
  {:vector/macro-press
   (fn [cell compile]
     (str "&macro_" (name (first cell)) " " (compile (second cell))))

   :vector/macro-release
   (fn [cell compile]
     (str "&macro_" (name (first cell)) " " (compile (second cell))))

   :vector/macro-tap
   (fn [cell compile]
     (str "&macro_" (name (first cell)) " " (compile (second cell))))

   :vector/wait
   (fn [cell _compile]
     (str "&macro_wait_time " (second cell)))

   :vector/tap-time
   (fn [cell _compile]
     (str "&macro_tap_time " (second cell)))

   :vector/pause
   (fn [_cell _compile]
     "&macro_pause_for_release")

   :vector/param-op
   (fn [cell _compile]
     (str "&macro_param_" (subs (name (first cell)) 6)))

   :vector/generic
   (fn [cell _compile]
     (str "&" (token->str (first cell))
          (when (seq (rest cell))
            (str " " (str/join " " (map token->str (rest cell)))))))

   :keyword/trans
   (fn [_cell _compile]
     "&trans")

   :keyword/none
   (fn [_cell _compile]
     "&none")

   :keyword/param-op
   (fn [cell _compile]
     (str "&macro_param_" (subs (name cell) 6)))

   :keyword/generic
   (fn [cell _compile]
     (str "&kp " (name cell)))

   :primitive
   (fn [cell _compile]
     (str cell))})

(defn compile-binding
  "Compile a binding cell into a ZMK binding string.
   This is the only public interface to the Binding Compiler."
  [cell]
  (if-let [compiler (compilers (dispatch-key cell))]
    (compiler cell compile-binding)
    (throw (ex-info (str "No compiler for dispatch key: " (dispatch-key cell))
                    {:cell cell :dispatch-key (dispatch-key cell)}))))

;; --- Rich comment tests ----------------------------------------------------

^:rct/test
(comment
  (compile-binding :P)
  ;=> "&kp P"

  (compile-binding [:lt 3 :DE_S])
  ;=> "&lt 3 DE_S"

  (compile-binding [:press :A])
  ;=> "&macro_press &kp A"

  (compile-binding [:release [:mo 2]])
  ;=> "&macro_release &mo 2"

  (compile-binding [:wait 30])
  ;=> "&macro_wait_time 30"

  (compile-binding [:tap-time 50])
  ;=> "&macro_tap_time 50"

  (compile-binding [:pause])
  ;=> "&macro_pause_for_release"

  (compile-binding :trans)
  ;=> "&trans"

  (compile-binding :none)
  ;=> "&none"

  (compile-binding :param-1to1)
  ;=> "&macro_param_1to1"

  (compile-binding 0)
  ;=> "0"

  :rcf)
