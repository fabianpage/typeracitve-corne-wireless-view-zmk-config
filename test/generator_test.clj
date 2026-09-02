(ns generator-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [generator :as generator]
            [bindings :as bindings]
            [com.mjdowney.rich-comment-tests.test-runner :as test-runner]))

(defn ^:private tokenize
  "Split a string on any whitespace, returning a sequence of non-empty tokens.";
  [s]
  (->> (str/split s #"\s+")
       (remove str/blank?)))

(defn ^:private discover-examples
  "Find all example configs in examples/ and return a seq of
   {:num <n> :config <path> :in <path> :out <path>} maps.";
  []
  (let [dir (io/file "examples")
        edn-files (sort (.listFiles dir
                         (reify java.io.FilenameFilter
                           (accept [_ _ name]
                             (.endsWith name ".edn")))))]
    (for [f edn-files
          :let [name (.getName f)
                num-str (first (str/split name #"\."))
                num (parse-long num-str)]]
      {:num num
       :config (.getPath f)
       :in (str "examples/" num "_in.keymap")
       :out (str "examples/" num "_out.keymap")})))

(defn ^:private node-block
  [node-name rendered]
  (let [pattern (re-pattern (str "(?s)\\n\\s*" (java.util.regex.Pattern/quote node-name) " \\{.*?\\n\\s*\\};"))]
    (re-find pattern rendered)))

(defn ^:private region-body
  [region rendered]
  (let [quoted-region (java.util.regex.Pattern/quote (name region))
        pattern (re-pattern (str "(?s)// BEGIN " quoted-region "\\n(.*?)\\n\\s*// END " quoted-region))]
    (second (re-find pattern rendered))))

(defn ^:private combo-node-blocks
  [rendered]
  (let [body (region-body :combos rendered)
        pattern #"(?ms)^\s*([A-Za-z0-9_]+)\s+\{.*?^\s*\};"]
    (into {}
          (map (fn [[block name]] [name block]))
          (re-seq pattern body))))

(defn ^:private balanced-braces?
  [s]
  (zero? (reduce (fn [depth ch]
                   (cond
                     (neg? depth) (reduced depth)
                     (= ch \{) (inc depth)
                     (= ch \}) (dec depth)
                     :else depth))
                 0
                 s)))

(defmacro ^:private deftest-examples
  "Generate one deftest per discovered example at macro-expansion time."
  []
  `(do
     ~@(for [{:keys [num config in out]} (discover-examples)
             :let [test-name (symbol (str "example-" num "-generates-expected-keymap"))]]
         `(deftest ~test-name
            (let [cfg# (generator/load-config ~config)
                  template# (slurp ~in)
                  expected# (slurp ~out)
                  generated# (generator/generate-keymap template# cfg#)]
              (is (= (tokenize expected#)
                     (tokenize generated#))
                                     (str "Example " ~config " did not generate expected output (whitespace-agnostic comparison)")))))))

(deftest-examples)

(deftest corne-config-generates-captured-baseline
  (let [config (generator/load-config "corne_config.edn")
        template (slurp "corne_template.keymap")
        expected (slurp "examples/corne_generated_baseline.keymap")
        generated (generator/generate-keymap template config)]
    (is (= expected generated)
        "corne_config.edn + corne_template.keymap must regenerate the captured pre-refactor keymap baseline exactly.")))

(deftest nav1-arrows-hjkl-order
  (let [config (generator/load-config "corne_config.edn")
        template (slurp "corne_template.keymap")
        generated (generator/generate-keymap template config)]
    (is (re-find #"&none &B_OE &kp LEFT_ARROW &kp DOWN &kp UP &kp RIGHT_ARROW"
                 generated)
        "Nav1 row-2 nav cells must render left-to-right as LEFT_ARROW DOWN UP RIGHT_ARROW (hjkl-aligned).")))

(deftest missing-markers-throws
  (let [config {:regions [[:keymap {:raw-body? true
                                    :nodes [{:name "base_layer"
                                             :body ["    display-name = \"BASE\";"]}]}]]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Could not find markers in template"
         (generator/generate-keymap "keymap {}" config)))))

(deftest binding-dsl-compiles-cells
  (is (= "&kp P" (bindings/compile-binding :P)))
  (is (= "&lt 3 DE_S" (bindings/compile-binding [:lt 3 :DE_S])))
  (is (= "&bt BT_SEL 0" (bindings/compile-binding [:bt :BT_SEL 0])))
  (is (= "&trans" (bindings/compile-binding :trans)))
  (is (= "&none" (bindings/compile-binding :none))))

(deftest binding-dsl-compiles-press-release-tap-wrappers
  (is (= "&macro_press &kp A" (bindings/compile-binding [:press :A])))
  (is (= "&macro_release &kp B" (bindings/compile-binding [:release :B])))
  (is (= "&macro_tap &kp C" (bindings/compile-binding [:tap :C])))
  ;; wrappers compose with vector bindings
  (is (= "&macro_press &mo 2" (bindings/compile-binding [:press [:mo 2]])))
  (is (= "&macro_release &lt 3 DE_S" (bindings/compile-binding [:release [:lt 3 :DE_S]])))
  (is (= "&macro_tap &bt BT_SEL 0" (bindings/compile-binding [:tap [:bt :BT_SEL 0]]))))

(deftest resolve-alias-expands-keywords-recursively
  (let [aliases {:_ :trans :trans :none :S [:lt 3 :DE_S]}]
    (is (= :none (generator/resolve-alias aliases :_)))
    (is (= [:lt 3 :DE_S] (generator/resolve-alias aliases :S)))
    (is (= :P (generator/resolve-alias aliases :P)))))

(deftest aliases-expand-in-keymap-bindings
  (let [template "    // BEGIN keymap\n    // END keymap\n"
        config {:aliases {:_ :trans :S [:lt 3 :DE_S]}
                :regions [[:keymap {:nodes [{:name "BASE"
                                             :bindings [[:S :A :_]]}]}]]}]
    (is (re-find #"BASE \{" (generator/generate-keymap template config)))
    (is (re-find #"&lt 3 DE_S &kp A &trans"
                 (generator/generate-keymap template config)))))

(deftest layer-generates-display-name-from-name
  (let [rendered (generator/render-layer {:name "BASE"
                                          :bindings [[:P :O]
                                                     [[:lt 3 :DE_S] :A]]}
                                         2)]
    (is (re-find #"BASE \{" rendered))
    (is (re-find #"display-name = \"BASE\";" rendered))
    (is (re-find #"&kp P &kp O" rendered))
    (is (re-find #"&lt 3 DE_S &kp A" rendered))))

(deftest combo-layer-generates-combos
  (let [template "    // BEGIN combos
    // END combos
    // BEGIN keymap
    // END keymap
"
        config {:regions [[:combos
                           {:nodes [{:name "diag"
                                      :type :combo-layer
                                      :row-widths [3 3]
                                      :pattern [[0 0] [1 1]]
                                      :bindings [[:Q :W :E]
                                                 [:A :S :D]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :W :E]
                                                [:A :S :D]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "diag_0_0"))
    (is (str/includes? generated "key-positions = <0 4>;"))
    (is (str/includes? generated "bindings = <&kp Q>;"))
    (is (str/includes? generated "diag_0_1"))
    (is (str/includes? generated "key-positions = <1 5>;"))
    (is (str/includes? generated "bindings = <&kp W>;"))
    (is (not (str/includes? generated "diag_1_0")))
    (is (not (str/includes? generated "diag_1_1")))
    (is (not (str/includes? generated "diag_0_2")))
    (is (not (str/includes? generated "diag_1_2")))))

(deftest combo-layer-skips-none-and-trans
  (let [template "    // BEGIN combos
    // END combos
    // BEGIN keymap
    // END keymap
"
        config {:regions [[:combos
                           {:nodes [{:name "diag"
                                      :type :combo-layer
                                      :row-widths [3 3]
                                      :pattern [[0 0] [1 1]]
                                      :bindings [[:Q :none :trans]
                                                 [:trans :S :none]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :none :trans]
                                                [:trans :S :none]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "diag_0_0"))
    (is (not (str/includes? generated "diag_0_1")))
    (is (not (str/includes? generated "diag_0_2")))
    (is (not (str/includes? generated "diag_1_0")))
    (is (not (str/includes? generated "diag_1_1")))
    (is (not (str/includes? generated "diag_1_2")))))

(deftest render-behavior-macro-0-param-generates-macro-node
  (let [rendered (generator/render-behavior {:name "hello"
                                             :type :macro
                                             :bindings [:H :E :L :L :O]}
                                            2)]
    (is (str/includes? rendered "hello: hello {"))
    (is (str/includes? rendered "compatible = \"zmk,behavior-macro\";"))
    (is (str/includes? rendered "#binding-cells = <0>;"))
    (is (str/includes? rendered "bindings = <&kp H &kp E &kp L &kp L &kp O>;"))
    (is (not (str/includes? rendered "wait-ms")))
    (is (not (str/includes? rendered "tap-ms")))))

(deftest render-behavior-macro-emits-wait-ms-only
  (let [rendered (generator/render-behavior {:name "slow-wait"
                                             :type :macro
                                             :bindings [:A :B]
                                             :wait-ms 80}
                                            2)]
    (is (str/includes? rendered "wait-ms = <80>;"))
    (is (not (str/includes? rendered "tap-ms")))))

(deftest render-behavior-macro-emits-tap-ms-only
  (let [rendered (generator/render-behavior {:name "slow-tap"
                                             :type :macro
                                             :bindings [:A :B]
                                             :tap-ms 20}
                                            2)]
    (is (str/includes? rendered "tap-ms = <20>;"))
    (is (not (str/includes? rendered "wait-ms")))))

(deftest render-behavior-macro-with-wrapper-bindings
  (let [rendered (generator/render-behavior {:name "ctrl_a"
                                             :type :macro
                                             :bindings [[:press :LCTRL] :A [:release :LCTRL]]}
                                            2)]
    (is (str/includes? rendered "bindings = <&macro_press &kp LCTRL &kp A &macro_release &kp LCTRL>;"))))

(deftest render-behavior-emits-wait-ms-and-tap-ms
  (let [rendered (generator/render-behavior {:name "slow"
                                             :type :macro
                                             :bindings [:A :B]
                                             :wait-ms 40
                                             :tap-ms 30}
                                            2)]
    (is (str/includes? rendered "wait-ms = <40>;"))
    (is (str/includes? rendered "tap-ms = <30>;"))))

(deftest aliases-expand-inside-macro-bodies
  (let [template "    // BEGIN macros
    // END macros
    // BEGIN keymap
    // END keymap
"
        config {:aliases {:ESC :ESCAPE :CTRL [:lt 2 :LCTRL]}
                :regions [[:macros
                           {:nodes [{:name "esc_macro"
                                     :type :macro
                                     :bindings [:ESC [:press :CTRL] [:wait 30] [:release :CTRL]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :W :E]
                                                [:A :S :D]]}]}]]}]
    (is (re-find #"bindings = <&kp ESCAPE &macro_press &lt 2 LCTRL &macro_wait_time 30 &macro_release &lt 2 LCTRL>"
                 (generator/generate-keymap template config)))
    (is (re-find #"esc_macro" (generator/generate-keymap template config)))))

(deftest binding-dsl-compiles-macro-timing-steps
  (is (= "&macro_wait_time 30" (bindings/compile-binding [:wait 30])))
  (is (= "&macro_tap_time 50" (bindings/compile-binding [:tap-time 50])))
  (is (= "&macro_pause_for_release" (bindings/compile-binding [:pause]))))

(deftest render-behavior-macro-with-mixed-timing-bindings-and-wrappers
  (let [rendered (generator/render-behavior {:name "combo_macro"
                                             :type :macro
                                             :bindings [[:press :LCTRL]
                                                        [:wait 30]
                                                        :A
                                                        [:tap-time 50]
                                                        [:pause]
                                                        :B
                                                        [:release :LCTRL]]}
                                            2)]
    (is (str/includes? rendered "bindings = <&macro_press &kp LCTRL &macro_wait_time 30 &kp A &macro_tap_time 50 &macro_pause_for_release &kp B &macro_release &kp LCTRL>;"))))

(deftest render-behavior-mod-morph-renders-correctly
  (let [rendered (generator/render-behavior {:name "comma_morph"
                                              :type :mod-morph
                                              :bindings [:COMMA :SEMICOLON]
                                              :label "COMMA_MORPH"
                                              :mods "(MOD_LGUI)"}
                                             2)]
    (is (str/includes? rendered "comma_morph: COMMA_MORPH {"))
    (is (str/includes? rendered "compatible = \"zmk,behavior-mod-morph\";"))
    (is (str/includes? rendered "#binding-cells = <0>;"))
    (is (str/includes? rendered "bindings = <&kp COMMA>, <&kp SEMICOLON>;"))
    (is (str/includes? rendered "mods = <(MOD_LGUI)>;"))))

(deftest render-behavior-boolean-flag-renders-as-flag
  (let [rendered (generator/render-behavior {:name "ht"
                                             :type :macro
                                             :bindings [:A]
                                             :global-quick-tap true}
                                            2)]
    (is (str/includes? rendered "global-quick-tap;"))
    (is (not (str/includes? rendered "global-quick-tap = <true>;")))
    (is (not (str/includes? rendered "global-quick-tap = \"true\";")))))

(deftest render-behavior-string-value-renders-quoted
  (let [rendered (generator/render-behavior {:name "ht"
                                             :type :macro
                                             :bindings [:A]
                                             :flavor "balanced"}
                                            2)]
    (is (str/includes? rendered "flavor = \"balanced\";"))
    (is (not (str/includes? rendered "flavor = <balanced>;")))))

(deftest half-key-positions-computes-opposite-half-from-row-widths
  (is (= [0 1 2 3 4 5 12 13 14 15 16 17 24 25 26 27 28 29]
         (vec (generator/half-key-positions [12 12 12 6] :left)))
      "left half excludes the narrower thumb row")
  (is (= [6 7 8 9 10 11 18 19 20 21 22 23 30 31 32 33 34 35]
         (vec (generator/half-key-positions [12 12 12 6] :right)))
      "right half excludes the narrower thumb row"))

(deftest render-behavior-resolves-half-position-shorthand
  (let [rendered (generator/render-behavior {:name "lh_ht"
                                             :type :hold-tap
                                             :bindings ["&kp" "&kp"]
                                             :hold-trigger-key-positions :right-half}
                                            2 false {:keyboard {:row-widths [12 12 12 6]}})]
    (is (str/includes? rendered "hold-trigger-key-positions = <6 7 8 9 10 11 18 19 20 21 22 23 30 31 32 33 34 35>;"))))

(deftest render-behavior-vector-value-renders-as-array
  (let [rendered (generator/render-behavior {:name "ht"
                                             :type :macro
                                             :bindings [:A]
                                             :hold-trigger-key-positions [0 1 2]}
                                            2)]
    (is (str/includes? rendered "hold-trigger-key-positions = <0 1 2>;"))
    (is (not (str/includes? rendered "hold-trigger-key-positions = <[0 1 2]>;")))))

(deftest render-behavior-omits-label-when-absent
  (let [rendered (generator/render-behavior {:name "hello"
                                              :type :macro
                                              :bindings [:H :E :L :L :O]}
                                             2)]
    (is (str/includes? rendered "hello: hello {"))
    (is (not (str/includes? rendered "hello: hello: ")))))

(deftest render-behavior-includes-label-when-present
  (let [rendered (generator/render-behavior {:name "hello"
                                              :type :macro
                                              :bindings [:H]
                                              :label "HELLO"}
                                             2)]
    (is (str/includes? rendered "hello: HELLO {"))))

(deftest render-behavior-unsupported-type-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported behavior type: :fantasy"
       (generator/render-behavior {:name "bad"
                                    :type :fantasy
                                    :bindings [:A]}
                                   2))))

(deftest combo-layer-resolves-layer-names
  (let [template "    // BEGIN combos
    // END combos
    // BEGIN keymap
    // END keymap
"
        config {:regions [[:combos
                           {:nodes [{:name "diag"
                                      :type :combo-layer
                                      :row-widths [3 3]
                                      :pattern [[0 0] [1 1]]
                                      :bindings [[:Q :W :E]
                                                 [:A :S :D]]
                                      :layers [:BASE]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :W :E]
                                                [:A :S :D]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "layers = <0>;"))))

(deftest raw-node-resolves-layer-names
  (let [template "    // BEGIN combos
    // END combos
    // BEGIN keymap
    // END keymap
"
        config {:regions [[:combos
                           {:raw-body? true
                            :nodes [{:name "raw_combo"
                                     :body ["bindings = <&kp ESC>;"
                                            "key-positions = <0 1>;"
                                            "timeout-ms = <30>;"]
                                     :layers [:BASE]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :W :E]
                                                [:A :S :D]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "raw_combo {"))
    (is (str/includes? generated "bindings = <&kp ESC>;"))
    (is (str/includes? generated "layers = <0>;"))))

(deftest raw-node-without-layers-remains-supported
  (let [template "    // BEGIN combos
    // END combos
"
        config {:regions [[:combos
                           {:raw-body? true
                            :nodes [{:name "raw_combo"
                                     :body ["bindings = <&kp ESC>;"
                                            "key-positions = <0 1>;"
                                            "timeout-ms = <30>;"]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "bindings = <&kp ESC>;"))
    (is (not (str/includes? generated "layers = <")))))

(deftest raw-node-unknown-layer-name-throws
  (let [template "    // BEGIN combos
    // END combos
    // BEGIN keymap
    // END keymap
"
        config {:regions [[:combos
                           {:raw-body? true
                            :nodes [{:name "raw_combo"
                                     :body ["bindings = <&kp ESC>;"
                                            "key-positions = <0 1>;"]
                                     :layers [:NOT_A_LAYER]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :W :E]]}]}]]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown layer name"
         (generator/generate-keymap template config)))))

(deftest horizontal-base-combos-render-from-base-scoped-combo-layers
  (let [generated (generator/generate-keymap (slurp "examples/1_in.keymap")
                                             (generator/load-config "examples/1.edn"))
        horizontal-combos [{:name "horizontal_rtl_0_4"
                            :binding "bindings = <&kp DE_Z>;"
                            :key-positions "key-positions = <4 3>;"}
                           {:name "horizontal_rtl_0_3"
                            :binding "bindings = <&kp DE_M>;"
                            :key-positions "key-positions = <3 2>;"}
                           {:name "horizontal_ltr_0_1"
                            :binding "bindings = <&kp DE_W>;"
                            :key-positions "key-positions = <1 2>;"}
                           {:name "horizontal_ltr_0_0"
                            :binding "bindings = <&kp DE_X>;"
                            :key-positions "key-positions = <0 1>;"}
                           {:name "horizontal_ltr_1_3"
                            :binding "bindings = <&kp DE_G>;"
                            :key-positions "key-positions = <13 14>;"}
                           {:name "horizontal_rtl_1_3"
                            :binding "bindings = <&kp DE_V>;"
                            :key-positions "key-positions = <13 12>;"}
                           {:name "horizontal_ltr_1_1"
                            :binding "bindings = <&kp TAB>;"
                            :key-positions "key-positions = <11 12>;"}
                           {:name "horizontal_ltr_1_0"
                            :binding "bindings = <&kp DE_Q>;"
                            :key-positions "key-positions = <10 11>;"}
                           {:name "horizontal_rtl_2_5"
                            :binding "bindings = <&kp DE_B>;"
                            :key-positions "key-positions = <25 24>;"}
                           {:name "horizontal_rtl_2_4"
                            :binding "bindings = <&kp DE_J>;"
                            :key-positions "key-positions = <24 23>;"}
                           {:name "horizontal_ltr_2_2"
                            :binding "bindings = <&kp DE_K>;"
                            :key-positions "key-positions = <22 23>;"}
                           {:name "horizontal_ltr_2_1"
                            :binding "bindings = <&kp DE_Y>;"
                            :key-positions "key-positions = <21 22>;"}]
        old-raw-names ["kpz" "kpm" "kpw" "kpx" "kpg" "kpv"
                       "kptap" "kpq" "kpb" "kpj" "kpk" "kpy"]]
    (doseq [{:keys [name binding key-positions]} horizontal-combos]
      (let [block (node-block name generated)]
        (is (str/starts-with? name "horizontal_") (str name " has horizontal-oriented prefix"))
        (is block (str name " generated combo node is present"))
        (when block
          (is (str/includes? block binding) (str name " renders the expected binding"))
          (is (str/includes? block key-positions) (str name " preserves key positions"))
          (is (str/includes? block "layers = <0>;") (str name " is scoped to BASE only")))))
    (doseq [old-name old-raw-names]
      (is (nil? (node-block old-name generated))
          (str old-name " old raw horizontal combo node is not rendered")))))

(deftest vertical-base-combos-render-from-base-scoped-combo-layer
  (let [generated (generator/generate-keymap (slurp "examples/1_in.keymap")
                                             (generator/load-config "examples/1.edn"))
        vertical-combos [{:name "vertical_0_4"
                          :binding "bindings = <&sk LEFT_GUI>;"
                          :key-positions "key-positions = <4 14>;"}
                         {:name "vertical_0_3"
                          :binding "bindings = <&sk LEFT_ALT>;"
                          :key-positions "key-positions = <3 13>;"}
                         {:name "vertical_0_2"
                          :binding "bindings = <&esc_layerreset>;"
                          :key-positions "key-positions = <2 12>;"}
                         {:name "vertical_0_1"
                          :binding "bindings = <&round_brackets>;"
                          :key-positions "key-positions = <1 11>;"}
                         {:name "vertical_0_0"
                          :binding "bindings = <&backspace_delete>;"
                          :key-positions "key-positions = <0 10>;"}]
        old-raw-names ["kpgui" "kpalt" "kpesc" "round_brackets" "backspace_delete"]]
    (doseq [{:keys [name binding key-positions]} vertical-combos]
      (let [block (node-block name generated)]
        (is (str/starts-with? name "vertical_") (str name " has vertical-oriented prefix"))
        (is block (str name " generated combo node is present"))
        (when block
          (is (str/includes? block binding) (str name " renders the expected binding"))
          (is (str/includes? block key-positions) (str name " preserves key positions"))
          (is (str/includes? block "layers = <0>;") (str name " is scoped to BASE only")))))
    (doseq [old-name old-raw-names]
      (is (nil? (node-block old-name generated))
          (str old-name " old raw vertical combo node is not rendered")))))

(deftest diagonal-base-combos-render-from-base-scoped-combo-layers
  (let [generated (generator/generate-keymap (slurp "examples/1_in.keymap")
                                             (generator/load-config "examples/1.edn"))
        diagonal-combos [{:name "diagonal_down_right_1_4"
                          :binding "bindings = <&sk LCTRL>;"
                          :key-positions "key-positions = <14 25>;"}
                         {:name "diagonal_down_right_1_3"
                          :binding "bindings = <&caps_word>;"
                          :key-positions "key-positions = <13 24>;"}
                         {:name "diagonal_down_right_reverse_2_3"
                          :binding "bindings = <&kp SPACE>;"
                          :key-positions "key-positions = <23 12>;"}
                         {:name "diagonal_down_right_1_1"
                          :binding "bindings = <&square_brackets>;"
                          :key-positions "key-positions = <11 22>;"}
                         {:name "diagonal_down_right_1_0"
                          :binding "bindings = <&kp ENTER>;"
                          :key-positions "key-positions = <10 21>;"}
                         {:name "diagonal_down_right_reverse_1_4"
                          :binding "bindings = <&curly_brackets>;"
                          :key-positions "key-positions = <14 3>;"}
                         {:name "diagonal_down_right_0_2"
                          :binding "bindings = <&punkt_doppelpunkt>;"
                          :key-positions "key-positions = <2 13>;"}]
        old-raw-names ["kpctrl" "kpcapsword" "kpspace" "square_brackets"
                       "enter" "curly_brackets" "punkt_doppelpunkt"]]
    (doseq [{:keys [name binding key-positions]} diagonal-combos]
      (let [block (node-block name generated)]
        (is (str/starts-with? name "diagonal_") (str name " has diagonal-oriented prefix"))
        (is block (str name " generated combo node is present"))
        (when block
          (is (str/includes? block binding) (str name " renders the expected binding"))
          (is (str/includes? block key-positions) (str name " preserves key positions"))
          (is (str/includes? block "layers = <0>;") (str name " is scoped to BASE only")))))
    (doseq [old-name old-raw-names]
      (is (nil? (node-block old-name generated))
          (str old-name " old raw diagonal combo node is not rendered")))))

(deftest retained-irregular-combos-render-as-base-scoped-raw-nodes
  (let [generated (generator/generate-keymap (slurp "examples/1_in.keymap")
                                             (generator/load-config "examples/1.edn"))
        retained-combos [{:name "angled_brackets"
                          :binding "bindings = <&angled_brackets>;"
                          :key-positions "key-positions = <25 13>;"}
                         {:name "komma_strichpunkt"
                          :binding "bindings = <&komma_strickpunkt>;"
                          :key-positions "key-positions = <24 12>;"}
                         {:name "toBT"
                          :binding "bindings = <&to 4>;"
                          :key-positions "key-positions = <1 2 3 4>;"}
                         {:name "ae"
                          :binding "bindings = <&M_UPPER_AEOEUE DE_A_UMLAUT>;"
                          :key-positions "key-positions = <33 21 32>;"}]]
    (doseq [{:keys [name binding key-positions]} retained-combos]
      (let [block (node-block name generated)]
        (is block (str name " raw combo node is present"))
        (is (str/includes? block binding) (str name " preserves binding"))
        (is (str/includes? block key-positions) (str name " preserves key positions"))
        (is (str/includes? block "layers = <0>;") (str name " is scoped to BASE only"))))))

(deftest complete-base-scoped-combo-migration-renders-only-inventory-combos
  (let [generated (generator/generate-keymap (slurp "examples/1_in.keymap")
                                             (generator/load-config "examples/1.edn"))
        expected-combos [{:name "horizontal_rtl_0_4" :group :horizontal :binding "bindings = <&kp DE_Z>;" :key-positions "key-positions = <4 3>;"}
                         {:name "horizontal_rtl_0_3" :group :horizontal :binding "bindings = <&kp DE_M>;" :key-positions "key-positions = <3 2>;"}
                         {:name "horizontal_ltr_0_1" :group :horizontal :binding "bindings = <&kp DE_W>;" :key-positions "key-positions = <1 2>;"}
                         {:name "horizontal_ltr_0_0" :group :horizontal :binding "bindings = <&kp DE_X>;" :key-positions "key-positions = <0 1>;"}
                         {:name "horizontal_ltr_1_3" :group :horizontal :binding "bindings = <&kp DE_G>;" :key-positions "key-positions = <13 14>;"}
                         {:name "horizontal_rtl_1_3" :group :horizontal :binding "bindings = <&kp DE_V>;" :key-positions "key-positions = <13 12>;"}
                         {:name "horizontal_ltr_1_1" :group :horizontal :binding "bindings = <&kp TAB>;" :key-positions "key-positions = <11 12>;"}
                         {:name "horizontal_ltr_1_0" :group :horizontal :binding "bindings = <&kp DE_Q>;" :key-positions "key-positions = <10 11>;"}
                         {:name "horizontal_rtl_2_5" :group :horizontal :binding "bindings = <&kp DE_B>;" :key-positions "key-positions = <25 24>;"}
                         {:name "horizontal_rtl_2_4" :group :horizontal :binding "bindings = <&kp DE_J>;" :key-positions "key-positions = <24 23>;"}
                         {:name "horizontal_ltr_2_2" :group :horizontal :binding "bindings = <&kp DE_K>;" :key-positions "key-positions = <22 23>;"}
                         {:name "horizontal_ltr_2_1" :group :horizontal :binding "bindings = <&kp DE_Y>;" :key-positions "key-positions = <21 22>;"}
                         {:name "vertical_0_4" :group :vertical :binding "bindings = <&sk LEFT_GUI>;" :key-positions "key-positions = <4 14>;"}
                         {:name "vertical_0_3" :group :vertical :binding "bindings = <&sk LEFT_ALT>;" :key-positions "key-positions = <3 13>;"}
                         {:name "vertical_0_2" :group :vertical :binding "bindings = <&esc_layerreset>;" :key-positions "key-positions = <2 12>;"}
                         {:name "vertical_0_1" :group :vertical :binding "bindings = <&round_brackets>;" :key-positions "key-positions = <1 11>;"}
                         {:name "vertical_0_0" :group :vertical :binding "bindings = <&backspace_delete>;" :key-positions "key-positions = <0 10>;"}
                         {:name "diagonal_down_right_1_4" :group :diagonal :binding "bindings = <&sk LCTRL>;" :key-positions "key-positions = <14 25>;"}
                         {:name "diagonal_down_right_1_3" :group :diagonal :binding "bindings = <&caps_word>;" :key-positions "key-positions = <13 24>;"}
                         {:name "diagonal_down_right_reverse_2_3" :group :diagonal :binding "bindings = <&kp SPACE>;" :key-positions "key-positions = <23 12>;"}
                         {:name "diagonal_down_right_1_1" :group :diagonal :binding "bindings = <&square_brackets>;" :key-positions "key-positions = <11 22>;"}
                         {:name "diagonal_down_right_1_0" :group :diagonal :binding "bindings = <&kp ENTER>;" :key-positions "key-positions = <10 21>;"}
                         {:name "diagonal_down_right_reverse_1_4" :group :diagonal :binding "bindings = <&curly_brackets>;" :key-positions "key-positions = <14 3>;"}
                         {:name "diagonal_down_right_0_2" :group :diagonal :binding "bindings = <&punkt_doppelpunkt>;" :key-positions "key-positions = <2 13>;"}
                         {:name "angled_brackets" :group :raw :binding "bindings = <&angled_brackets>;" :key-positions "key-positions = <25 13>;"}
                         {:name "komma_strichpunkt" :group :raw :binding "bindings = <&komma_strickpunkt>;" :key-positions "key-positions = <24 12>;"}
                         {:name "toBT" :group :raw :binding "bindings = <&to 4>;" :key-positions "key-positions = <1 2 3 4>;"}
                         {:name "ae" :group :raw :binding "bindings = <&M_UPPER_AEOEUE DE_A_UMLAUT>;" :key-positions "key-positions = <33 21 32>;"}]
        combo-blocks (combo-node-blocks generated)
        expected-names (set (map :name expected-combos))
        generated-names (set (keys combo-blocks))
        generated-combo-names (remove #(= :raw (:group %)) expected-combos)
        raw-combo-names (->> expected-combos (filter #(= :raw (:group %))) (map :name) set)]
    (is (balanced-braces? generated) "rendered keymap has balanced devicetree braces")
    (is (= 28 (count combo-blocks)) "rendered combos match the 28-combo migration inventory count")
    (is (= expected-names generated-names) "rendered combos contain the migration inventory and no extras")
    (is (= #{"angled_brackets" "komma_strichpunkt" "toBT" "ae"}
           raw-combo-names)
        "retained irregular raw combos preserve semantic node names")
    (doseq [{:keys [name group binding key-positions]} expected-combos]
      (let [block (get combo-blocks name)]
        (is block (str name " is rendered"))
        (is (str/includes? block binding) (str name " preserves the inventory binding"))
        (is (str/includes? block key-positions) (str name " preserves the inventory key positions"))
        (is (str/includes? block "layers = <0>;") (str name " is scoped to BASE"))
        (is (not (re-find #"layers\s*=\s*<\s*>" block)) (str name " is not global"))
        (case group
          :horizontal (is (str/starts-with? name "horizontal_") (str name " uses a horizontal group prefix"))
          :vertical (is (str/starts-with? name "vertical_") (str name " uses a vertical group prefix"))
          :diagonal (is (str/starts-with? name "diagonal_") (str name " uses a diagonal group prefix"))
          :raw (is (contains? raw-combo-names name) (str name " remains a retained raw combo")))))
    (is (= 24 (count generated-combo-names)) "horizontal, vertical, and diagonal Combo-layers account for 24 generated combos")
    (is (empty? (filter #(re-find #"(?i)^(nav|num|bt)[_-]" (:name %)) generated-combo-names))
        "no Nav-, Num-, or BT-specific generated Combo-layer names are introduced")))

(deftest combo-layer-skips-out-of-bounds
  (let [template "    // BEGIN combos\n    // END combos\n    // BEGIN keymap\n    // END keymap\n"
        config {:regions [[:combos
                           {:nodes [{:name "diag"
                                      :type :combo-layer
                                      :row-widths [3 3 3]
                                      :pattern [[0 0] [1 1] [2 2]]
                                      :bindings [[:Q :W :E]
                                                 [:A :S :D]
                                                 [:Z :X :C]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :W :E]
                                                [:A :S :D]
                                                [:Z :X :C]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "diag_0_0"))
    (is (not (str/includes? generated "diag_0_1")))
    (is (not (str/includes? generated "diag_0_2")))
    (is (not (str/includes? generated "diag_1_0")))
    (is (not (str/includes? generated "diag_1_1")))
    (is (not (str/includes? generated "diag_1_2")))
    (is (not (str/includes? generated "diag_2_0")))
    (is (not (str/includes? generated "diag_2_1")))
    (is (not (str/includes? generated "diag_2_2")))))





(deftest combo-layer-left-rejects-bindings-and-left
  (let [template "    // BEGIN combos\n    // END combos\n"
        config {:keyboard {:row-widths [4]}
                :regions [[:combos
                           {:nodes [{:name "conflict"
                                      :type :combo-layer
                                      :pattern [[0 0] [0 1]]
                                      :bindings [[:A :B :C :D]]
                                      :left [[:A :B]]}]}]]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"both :bindings and :left"
         (generator/generate-keymap template config)))))

(deftest combo-layer-left-generates-both-halves
  (let [template "    // BEGIN combos\n    // END combos\n    // BEGIN keymap\n    // END keymap\n"
        config {:keyboard {:row-widths [4]}
                :regions [[:combos
                           {:nodes [{:name "horiz"
                                      :type :combo-layer
                                      :pattern [[0 0] [0 1]]
                                      :layers [:BASE]
                                      :left [[:A :none]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:A :X :X :A]]}]}]]}
        generated (generator/generate-keymap template config)]
    ;; left half uses original pattern (ltr)
    (is (str/includes? generated "horiz_0_0"))
    (is (str/includes? generated "key-positions = <0 1>;"))
    (is (str/includes? generated "bindings = <&kp A>;"))
    ;; right half uses mirrored pattern (rtl)
    (is (str/includes? generated "horiz_0_3"))
    (is (str/includes? generated "key-positions = <3 2>;"))
    ;; boundary :none cells are skipped
    (is (not (str/includes? generated "horiz_0_1")))
    (is (not (str/includes? generated "horiz_0_2")))))

(deftest combo-layer-left-with-right-override
  (let [template "    // BEGIN combos\n    // END combos\n    // BEGIN keymap\n    // END keymap\n"
        config {:keyboard {:row-widths [4]}
                :regions [[:combos
                           {:nodes [{:name "horiz"
                                      :type :combo-layer
                                      :pattern [[0 0] [0 1]]
                                      :left [[:A :B]]
                                      :right-override [[:X :*]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:A :B :X :A]]}]}]]}
        generated (generator/generate-keymap template config)]
    ;; left half: A at 0, B at 1
    (is (str/includes? generated "horiz_0_0"))
    (is (str/includes? generated "key-positions = <0 1>;"))
    (is (str/includes? generated "bindings = <&kp A>;"))
    (is (str/includes? generated "horiz_0_1"))
    (is (str/includes? generated "key-positions = <1 2>;"))
    (is (str/includes? generated "bindings = <&kp B>;"))
    ;; right half with override: X at col 2, mirrored A at col 3
    (is (str/includes? generated "horiz_0_2"))
    (is (str/includes? generated "key-positions = <2 1>;"))
    (is (str/includes? generated "bindings = <&kp X>;"))
    (is (str/includes? generated "horiz_0_3"))
    (is (str/includes? generated "key-positions = <3 2>;"))
    (is (str/includes? generated "bindings = <&kp A>;"))))

(deftest combo-layer-left-uses-mirrored-pattern-for-right-half
  (let [template "    // BEGIN combos\n    // END combos\n    // BEGIN keymap\n    // END keymap\n"
        config {:keyboard {:row-widths [4 4]}
                :regions [[:combos
                           {:nodes [{:name "diag"
                                      :type :combo-layer
                                      :pattern [[0 0] [1 1]]
                                      :left [[:A :none]
                                             [:none :B]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:A :none :none :A]
                                                [:none :B :B :none]]}]}]]}
        generated (generator/generate-keymap template config)]
    ;; left half, base [0,0]: original pattern [0,0]→[1,1] → positions <0 5>
    (is (str/includes? generated "diag_0_0"))
    (is (str/includes? generated "key-positions = <0 5>;"))
    ;; right half, base [0,3]: mirrored pattern [0,0]→[1,-1] → positions <3 6>
    (is (str/includes? generated "diag_0_3"))
    (is (str/includes? generated "key-positions = <3 6>;"))))

(deftest combo-layer-expands-aliases
  (let [template "    // BEGIN combos
    // END combos
    // BEGIN keymap
    // END keymap
"
        config {:aliases {:_ :trans}
                :regions [[:combos
                           {:nodes [{:name "diag"
                                      :type :combo-layer
                                      :row-widths [3 3]
                                      :pattern [[0 0] [1 1]]
                                      :bindings [[:Q :_ :E]
                                                 [:A :S :D]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :W :E]
                                                [:A :S :D]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "diag_0_0"))
    (is (not (str/includes? generated "diag_0_1")))))









(deftest combo-layer-requires-row-widths
  (let [template "    // BEGIN combos
    // END combos
    // BEGIN keymap
    // END keymap
"
        config {:regions [[:combos
                           {:nodes [{:name "diag"
                                      :type :combo-layer
                                      :pattern [[0 0] [1 1]]
                                      :bindings [[:Q]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q]]}]}]]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":row-widths is required"
         (generator/generate-keymap template config)))))










; (deftest rich-comment-tests
 (deftest replace-placeholder-swaps-placeholder
  (is (= :MACRO_PLACEHOLDER (generator/replace-placeholder :_placeholder)))
  (is (= [:kp :MACRO_PLACEHOLDER] (generator/replace-placeholder [:kp :_placeholder])))
  (is (= [:macro_tap [:kp :MACRO_PLACEHOLDER]] (generator/replace-placeholder [:macro_tap [:kp :_placeholder]]))))

(deftest binding-dsl-compiles-param-ops
  (is (= "&macro_param_1to1" (bindings/compile-binding :param-1to1)))
  (is (= "&macro_param_1to2" (bindings/compile-binding :param-1to2)))
  (is (= "&macro_param_2to1" (bindings/compile-binding :param-2to1)))
  (is (= "&macro_param_2to2" (bindings/compile-binding :param-2to2))))

(deftest render-behavior-one-param-generates-expected-output
  (let [rendered (generator/render-behavior {:name "upper"
                                             :type :macro-one-param
                                             :bindings [:CAPSLOCK [:pause] [:param-1to1 [:kp :_placeholder]] :CAPSLOCK]
                                             :wait-ms 80
                                             :tap-ms 80}
                                            2)]
    (is (str/includes? rendered "compatible = \"zmk,behavior-macro-one-param\";"))
    (is (str/includes? rendered "#binding-cells = <1>;"))
    (is (str/includes? rendered "<&kp CAPSLOCK>,"))
    (is (str/includes? rendered "<&macro_pause_for_release>,"))
    (is (str/includes? rendered "<&macro_param_1to1>,"))
    (is (str/includes? rendered "<&kp MACRO_PLACEHOLDER>,"))
    (is (str/includes? rendered "<&kp CAPSLOCK>;"))
    (is (str/includes? rendered "wait-ms = <80>;"))
    (is (str/includes? rendered "tap-ms = <80>;"))))

(deftest render-behavior-two-param-generates-expected-output
  (let [rendered (generator/render-behavior {:name "swap"
                                             :type :macro-two-param
                                             :bindings [[:param-2to1 [:kp :_placeholder]] [:param-2to2 [:kp :_placeholder]]]
                                             :wait-ms 20}
                                            2)]
    (is (str/includes? rendered "compatible = \"zmk,behavior-macro-two-param\";"))
    (is (str/includes? rendered "#binding-cells = <2>;"))
    (is (str/includes? rendered "<&macro_param_2to1>,"))
    (is (str/includes? rendered "<&kp MACRO_PLACEHOLDER>,"))
    (is (str/includes? rendered "<&macro_param_2to2>,"))
    (is (str/includes? rendered "<&kp MACRO_PLACEHOLDER>;"))))

(deftest param-wrappers-compose-with-behavior-macro-tap
  (let [rendered (generator/render-behavior {:name "tap_param"
                                             :type :macro-one-param
                                             :bindings [[:param-1to1 [:macro_tap :_placeholder]]]}
                                            2)]
    (is (str/includes? rendered "<&macro_param_1to1>,"))
    (is (str/includes? rendered "<&macro_tap MACRO_PLACEHOLDER>;"))))

(deftest param-1to2-with-behavior-macro-tap-emits-expected-groups
  (let [rendered (generator/render-behavior {:name "param12"
                                             :type :macro-one-param
                                             :bindings [[:param-1to2 [:macro_tap :_placeholder]]]}
                                            2)]
    (is (str/includes? rendered "<&macro_param_1to2>,"))
    (is (str/includes? rendered "<&macro_tap MACRO_PLACEHOLDER>;"))))

; (test-runner/run-tests-in-file-tree! :dirs #{"./"} ))




(deftest assemble-layer-bindings-mirror-without-override
  (let [keyboard {:row-widths [4 4]}
        layer {:left [[:A :B]
                       [:C :D]]}]
    (is (= [[:A :B :B :A]
            [:C :D :D :C]]
           (generator/assemble-layer-bindings layer keyboard)))))

(deftest assemble-layer-bindings-with-override-sentinel
  (let [keyboard {:row-widths [4]}
        layer {:left [[:A :B]]
               :right-override [[:X :*]]}]
    (is (= [[:A :B :X :A]]
           (generator/assemble-layer-bindings layer keyboard)))))

(deftest assemble-layer-bindings-with-nil-override-row
  (let [keyboard {:row-widths [4 4 2]}
        layer {:left [[:A :B]
                       [:C :D]
                       [:E]]
               :right-override [nil [:X :Y] nil]}]
    (is (= [[:A :B :B :A]
            [:C :D :X :Y]
            [:E :E]]
           (generator/assemble-layer-bindings layer keyboard)))))

(deftest assemble-layer-bindings-missing-keyboard-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Missing :keyboard"
       (generator/assemble-layer-bindings {:left [[:A :B]]} nil))))

(deftest assemble-layer-bindings-missing-row-widths-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Missing :keyboard :row-widths"
       (generator/assemble-layer-bindings {:left [[:A :B]]} {}))))

(deftest assemble-layer-bindings-odd-row-widths-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"odd"
       (generator/assemble-layer-bindings {:left [[:A :B]]} {:row-widths [5]}))))

(deftest assemble-layer-bindings-left-row-length-mismatch-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"does not match half row-width"
       (generator/assemble-layer-bindings {:left [[:A :B :C]]} {:row-widths [4]}))))

(deftest assemble-layer-bindings-left-row-count-mismatch-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"row count does not match"
       (generator/assemble-layer-bindings {:left [[:A :B] [:C :D]]} {:row-widths [4]}))))

(deftest assemble-layer-bindings-override-row-count-mismatch-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"right-override row count"
       (generator/assemble-layer-bindings {:left [[:A :B]]
                                           :right-override [nil nil]}
                                          {:row-widths [4]}))))

(deftest assemble-layer-bindings-override-row-length-mismatch-throws
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"does not match half row-width"
       (generator/assemble-layer-bindings {:left [[:A :B]]
                                           :right-override [[:X :Y :Z]]}
                                          {:row-widths [4]}))))

(deftest generate-keymap-left-only-produces-symmetric-bindings
  (let [template "    // BEGIN keymap\n    // END keymap\n"
        config {:keyboard {:row-widths [4 4]}
                :regions [[:keymap
                           {:nodes [{:name "BASE"
                                     :left [[:A :B]
                                            [:C :D]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "BASE {"))
    (is (re-find #"&kp A &kp B &kp B &kp A" generated))
    (is (re-find #"&kp C &kp D &kp D &kp C" generated))))

(deftest generate-keymap-right-override-overrides-mirrored
  (let [template "    // BEGIN keymap\n    // END keymap\n"
        config {:keyboard {:row-widths [4]}
                :regions [[:keymap
                           {:nodes [{:name "BASE"
                                     :left [[:A :B]]
                                     :right-override [[:X :*]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (re-find #"&kp A &kp B &kp X &kp A" generated))))

(deftest combo-layer-inherits-row-widths-from-keyboard
  (let [template "    // BEGIN combos\n    // END combos\n    // BEGIN keymap\n    // END keymap\n"
        config {:keyboard {:row-widths [3 3]}
                :regions [[:combos
                           {:nodes [{:name "diag"
                                      :type :combo-layer
                                      :pattern [[0 0] [1 1]]
                                      :bindings [[:Q :W :E]
                                                 [:A :S :D]]}]}]
                          [:keymap
                           {:nodes [{:name "BASE"
                                     :bindings [[:Q :W :E]
                                                [:A :S :D]]}]}]]}
        generated (generator/generate-keymap template config)]
    (is (str/includes? generated "diag_0_0"))
    (is (str/includes? generated "key-positions = <0 4>;"))
    (is (str/includes? generated "bindings = <&kp Q>;"))))

(defn run
  []
  (let [{:keys [fail error] :as result} (run-tests 'generator-test)]
    (when (pos? (+ fail error))
      (throw (ex-info "Tests failed" result)))
    result))

(comment
  (run)
  )
