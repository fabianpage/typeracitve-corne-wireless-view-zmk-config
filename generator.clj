#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {aero/aero {:mvn/version "1.1.6"}}})

(ns generator
  (:require [aero.core :as aero]
            [babashka.cli :as cli]
            [bindings]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.util.regex Pattern]))

(defn indent
  [level]
  (str/join (repeat level "    ")))

(defn render-line
  [level line]
  (if (str/blank? line)
    ""
    (str (indent level) line)))

(defn replace-placeholder
  "Recursively walk a binding expression and replace :_placeholder with :MACRO_PLACEHOLDER."
  [cell]
  (cond
    (keyword? cell)
    (if (= cell :_placeholder) :MACRO_PLACEHOLDER cell)
    (vector? cell)
    (mapv replace-placeholder cell)
    :else cell))

(defn param-step?
  "Check if a cell is a param-forwarding wrapper."
  [cell]
  (and (vector? cell)
       (#{:param-1to1 :param-1to2 :param-2to1 :param-2to2} (first cell))))

(defn resolve-alias
  "Recursively resolve a binding cell through the aliases map.
   :_ -> :trans -> &trans (one or more levels). Vectors and non-alias keywords are returned as-is."
  [aliases cell]
  (if (and (keyword? cell) (contains? aliases cell))
    (recur aliases (get aliases cell))
    cell))

(defn expand-aliases
  "Recursively walk the full config map and expand alias keywords at every level.
   Previously this only expanded inside :bindings vectors of layer nodes."
  [config]
  (if-let [aliases (not-empty (:aliases config))]
    (walk/postwalk
      (fn [x]
        (resolve-alias aliases x))
      config)
    config))

(defn extract-layer-indexes
  "Build a map from layer name string to its 0-based index,
   by scanning the :keymap region nodes in the config."
  [config]
  (if-let [keymap-region (some (fn [[region spec]]
                                 (when (= region :keymap) spec))
                               (:regions config))]
    (into {} (map-indexed (fn [idx node]
                            [(name (:name node)) idx])
                           (:nodes keymap-region)))
    {}))

(defn assemble-layer-bindings
  "Given a layer node with :left (half-grid) and optional :right-override,
   produce a complete :bindings grid by mirroring each left row horizontally
   and applying overrides. Validates against :keyboard geometry.
   
   :*      - sentinel meaning 'use mirrored value'
   nil row - means 'use full mirrored row'"
  [{:keys [left right-override]} {:keys [row-widths] :as keyboard}]
  (when (nil? keyboard)
    (throw (ex-info "Missing :keyboard config"
                    {:keyboard keyboard})))
  (when-not (seq row-widths)
    (throw (ex-info "Missing :keyboard :row-widths in config"
                    {:keyboard keyboard})))
  (doseq [[idx w] (map-indexed vector row-widths)]
    (when-not (integer? w)
      (throw (ex-info (str ":row-widths entry " idx " is not an integer: " w)
                      {:row-widths row-widths :index idx :value w})))
    (when (odd? w)
      (throw (ex-info (str ":row-widths entry " idx " is odd: " w)
                      {:row-widths row-widths :index idx :value w}))))
  (when-not (seq left)
    (throw (ex-info ":left is required and must contain at least one row"
                    {:left left})))
  (when-not (= (count left) (count row-widths))
    (throw (ex-info ":left row count does not match :keyboard :row-widths"
                    {:left-row-count (count left)
                     :row-widths-count (count row-widths)})))
  (doseq [[idx left-row row-width] (map vector (range) left row-widths)]
    (let [expected-half (quot row-width 2)]
      (when-not (= (count left-row) expected-half)
        (throw (ex-info (str ":left row " idx " length (" (count left-row) 
                             ") does not match half row-width (" expected-half ")")
                        {:row-idx idx
                         :left-row left-row
                         :row-width row-width
                         :expected expected-half
                         :actual (count left-row)})))))
  (when right-override
    (when-not (= (count right-override) (count left))
      (throw (ex-info ":right-override row count does not match :left"
                      {:right-override-count (count right-override)
                       :left-count (count left)})))
    (doseq [[idx override-row row-width] (map vector (range) right-override row-widths)]
      (when (some? override-row)
        (let [expected-half (quot row-width 2)]
          (when-not (= (count override-row) expected-half)
            (throw (ex-info (str ":right-override row " idx " length (" (count override-row) 
                                 ") does not match half row-width (" expected-half ")")
                            {:row-idx idx
                             :override-row override-row
                             :row-width row-width
                             :expected expected-half
                             :actual (count override-row)})))))))
  (mapv (fn [idx left-row row-width]
          (let [mirrored (vec (reverse left-row))
                override-row (when right-override (nth right-override idx))
                right-half (if (nil? override-row)
                             mirrored
                             (mapv (fn [override-cell mirrored-cell]
                                     (if (= override-cell :*)
                                       mirrored-cell
                                       override-cell))
                                   override-row
                                   mirrored))]
            (into (vec left-row) right-half)))
        (range (count left))
        left
        row-widths))

(defn resolve-left-bindings
  "Walk config regions.
   For each node in the :keymap region that has :left, assemble a full :bindings
   grid by mirroring and applying :right-override. Any existing :bindings is
   overwritten.
   For each :combo-layer node:
   - if it declares both :bindings and :left, throw
   - if it declares :left, assemble full bindings and set :auto-symmetric? true
   - if it does not declare :row-widths, inject :keyboard :row-widths."
  [config]
  (let [keyboard (:keyboard config)]
    (update config :regions
            (fn [regions]
              (mapv (fn [[region spec]]
                      [region (update spec :nodes
                                      (fn [nodes]
                                        (mapv (fn [node]
                                                (cond
                                                  ;; :keymap region + :left → assemble full bindings
                                                  (and (= region :keymap) (:left node))
                                                  (let [bindings (assemble-layer-bindings node keyboard)]
                                                    (-> node
                                                        (dissoc :left :right-override)
                                                        (assoc :bindings bindings)))

                                                  ;; combo-layer → resolve :left or inject row-widths
                                                  (= (:type node) :combo-layer)
                                                  (let [has-bindings (seq (:bindings node))
                                                        has-left (seq (:left node))
                                                        node (if (:row-widths node)
                                                               node
                                                               (if keyboard
                                                                 (assoc node :row-widths (:row-widths keyboard))
                                                                 node))]
                                                    (cond
                                                      (and has-bindings has-left)
                                                      (throw (ex-info "Combo-layer may not declare both :bindings and :left"
                                                                      {:node node}))

                                                      has-left
                                                      (let [bindings (assemble-layer-bindings node {:row-widths (:row-widths node)})]
                                                        (-> node
                                                            (dissoc :left :right-override)
                                                            (assoc :bindings bindings
                                                                   :auto-symmetric? true)))

                                                      :else
                                                      node))

                                                  :else
                                                  node))
                                              nodes)))])
                    regions)))))

(defn combo-positions
  "Given row-widths, a pattern of [[row-off col-off] ...], and a base [row col],
   return the absolute ZMK key-positions in pattern order, or nil if any
   offset is out of bounds."
  [row-widths pattern [base-r base-c]]
  (let [num-rows (count row-widths)
        prefix-sums (reductions + 0 row-widths)]
    (when (every? (fn [[r-off c-off]]
                    (let [r (+ base-r r-off)
                          c (+ base-c c-off)]
                      (and (>= r 0) (< r num-rows)
                           (>= c 0) (< c (nth row-widths r)))))
                  pattern)
      (map (fn [[r-off c-off]]
             (let [r (+ base-r r-off)
                   c (+ base-c c-off)]
               (+ c (nth prefix-sums r))))
           pattern))))

(defn macro-binding-groups
  "Expand a macro body into a seq of <...> group contents.
   Most cells become one group. Param wrappers become two groups:
   the param control behavior, and the resolved binding with
   :_placeholder replaced by :MACRO_PLACEHOLDER."
  [body]
  (mapcat
    (fn [cell]
      (if (param-step? cell)
        (let [[param-op inner-binding] cell]
          [(str "&macro_param_" (subs (name param-op) 6))
           (bindings/compile-binding (replace-placeholder inner-binding))])
        [(bindings/compile-binding cell)]))
    body))

(def behavior-types
  "Registry of declarative ZMK behavior node types.
   Each entry specifies the DTS compatible string, #binding-cells count,
   and how the :bindings / :body vector should be formatted."
  {:mod-morph       {:compatible     "zmk,behavior-mod-morph"
                     :binding-cells  0
                     :binding-format :multi-bracket-comma}
   :smart-toggle    {:compatible     "zmk,behavior-smart-toggle"
                     :binding-cells  0
                     :binding-format :multi-bracket-comma}
   :macro           {:compatible     "zmk,behavior-macro"
                     :binding-cells  0
                     :binding-format :single-bracket-space}
   :macro-one-param {:compatible     "zmk,behavior-macro-one-param"
                     :binding-cells  1
                     :binding-format :macro-groups}
   :macro-two-param {:compatible     "zmk,behavior-macro-two-param"
                     :binding-cells  2
                     :binding-format :macro-groups}})

(defn- dt-string-value?
  "Return true if a string value should be rendered as a devicetree
   quoted string rather than an unquoted expression in angle brackets.
   Numeric strings and paren-wrapped expressions are NOT dt strings."
  [v]
  (not (or (re-matches #"-?\d+" v)
           (re-matches #"\(.*\)" v))))

(defn render-behavior
  "Render any registered behavior type as a ZMK devicetree node.
   :name      — DT node id (:label, if present, is used as the display-name)
   :type      — keyword looked up in `behavior-types`
   :bindings  — behavior-specific binding vector (overrides :body)
   :body      — alias for :bindings
   Any other keys are emitted as pass-through properties: key = <value>;"
  ([node level]
   (render-behavior node level false nil))
  ([{:keys [name type bindings body label] :as node} level _raw-body? _opts]
  (if-let [{:keys [compatible binding-cells binding-format]} (get behavior-types type)]
    (let [display-name   (or label name)
          reserved       #{:name :type :bindings :body :label}
          pass-through   (remove (comp reserved key) node)
          b              (or body bindings)
          bindings-line  (case binding-format
                           :single-bracket-space
                           (str (indent (inc level)) "bindings = <"
                                (str/join " " (map bindings/compile-binding b))
                                ">;")

                           :multi-bracket-comma
                           (str (indent (inc level)) "bindings = "
                                (str/join ", "
                                  (map #(str "<" (bindings/compile-binding %) ">") b))
                                ";")

                           :macro-groups
                           (let [groups (macro-binding-groups b)]
                             (str (indent (inc level)) "bindings =\n"
                                  (str/join ",\n"
                                            (map #(str (indent (inc level)) "    <" % ">")
                                                 groups))
                                  ";")))]
      (str/join
       "\n"
       (concat [(str (indent level) name ": " display-name " {")
                (str (indent (inc level)) "compatible = \"" compatible "\";")
                (str (indent (inc level)) "#binding-cells = <" binding-cells ">;")
                bindings-line]
               (map (fn [[k v]]
                      (str (indent (inc level)) (clojure.core/name k)
                           (cond
                             (true? v) ";"
                             (vector? v) (str " = <" (str/join " " v) ">;")
                             (and (string? v) (dt-string-value? v)) (str " = \"" v "\";")
                             :else (str " = <" v ">;"))))
                    pass-through)
               [(str (indent level) "};")] )))
    (throw (ex-info (str "Unsupported behavior type: " type) {:node node})))))

(defn render-layer
  "Render a keymap layer node. The :name doubles as the DT node id and the
   generated display-name. :bindings is a vector of rows, each a vector of cells."
  ([node level]
   (render-layer node level false nil))
  ([{:keys [name bindings]} level _raw-body? _opts]
  (str/join
   "\n"
   (concat [(str (indent level) name " {")
            (str (indent (inc level)) "display-name = \"" name "\";")
            (str (indent (inc level)) "bindings = <")]
           (map (fn [row] (str/join " " (map bindings/compile-binding row))) bindings)
            [(str (indent (inc level)) ">;")
             (str (indent level) "};")]))))

(defn resolve-layer-nums
  "Resolve layer references (keywords or raw indexes) into numeric indexes.
   Keywords are resolved via layer-index-map and unknown names throw." 
  [layers layer-index-map]
  (when (seq layers)
    (map (fn [layer]
           (if (keyword? layer)
             (if-let [idx (get layer-index-map (clojure.core/name layer))]
               idx
               (throw (ex-info (str "Unknown layer name: " layer)
                               {:layer layer :available (keys layer-index-map)})))
             layer))
         layers)))

(defn render-combo-layer
  "Render a :combo-layer node into one or more ZMK combo DT nodes.
   :row-widths is required. :pattern defines relative offsets.
   :bindings uses the normal binding DSL. :layers can be keywords
   (resolved against the keymap) or raw numbers.
   When :auto-symmetric? is true, the left half uses :pattern and the
   right half uses the horizontally-mirrored pattern (column offsets negated)."
  ([node level opts]
   (render-combo-layer node level false opts))
  ([{:keys [name row-widths pattern bindings layers auto-symmetric?] :as node} level _raw-body? {:keys [layer-index-map]}]
  (when-not row-widths
    (throw (ex-info ":row-widths is required for :combo-layer" {:node node})))
  (let [layer-nums (resolve-layer-nums layers layer-index-map)
        layer-line (when (seq layer-nums)
                     (str (indent (inc level)) "layers = <" (str/join " " layer-nums) ">;"))
        half-widths (when auto-symmetric? (mapv #(quot % 2) row-widths))
        mirrored-pattern (when auto-symmetric? (mapv (fn [[dr dc]] [dr (- dc)]) pattern))
        combos (for [r (range (count bindings))
                     c (range (count (nth bindings r)))
                     :let [cell (get-in bindings [r c])
                           effective-pattern (if (and auto-symmetric?
                                                      (>= c (nth half-widths r)))
                                               mirrored-pattern
                                               pattern)
                           positions (combo-positions row-widths effective-pattern [r c])]
                     :when (and positions
                                (not (#{:none :trans} cell)))]
                 (let [combo-name (str name "_" r "_" c)]
                   (str/join
                    "\n"
                    (concat [(str (indent level) combo-name " {")
                             (str (indent (inc level)) "bindings = <" (bindings/compile-binding cell) ">;")
                             (str (indent (inc level)) "key-positions = <" (str/join " " positions) ">;")]
                            (when layer-line [layer-line])
                            [(str (indent level) "};")]))))]
    (str/join "\n\n" combos))))

(defn render-raw-body
  "Render a raw-body node (falls through when no specific type matches)."
  [{:keys [name body layers label] :as node} level raw-body? {:keys [layer-index-map]}]
  (let [layer-nums (resolve-layer-nums layers layer-index-map)
        layer-line (when (seq layer-nums)
                     (str (indent (inc level)) "layers = <" (str/join " " layer-nums) ">;"))]
    (str/join
     "\n"
     (concat [(str (indent level)
                   name
                   (when label
                     (str ": " label))
                   " {")]
             (if raw-body?
               body
               (map #(render-line (inc level) %) body))
             (when layer-line [layer-line])
             [(str (indent level) "};")]))))

(defn node-type
  "Return the renderer registry key for a node."
  [node]
  (let [t (:type node)]
    (cond
      (= t :combo-layer)                :combo-layer
      (contains? behavior-types t)      :behavior
      (:bindings node)                  :layer
      :else                             :raw-body)))

(def renderer-registry
  "Flat registry of node-type → renderer adapter."
  {:combo-layer {:render-fn render-combo-layer}
   :layer       {:render-fn render-layer}
   :behavior    {:render-fn render-behavior}
   :raw-body    {:render-fn render-raw-body}})

(defn render-node [node level raw-body? opts]
  (if-let [{:keys [render-fn]} (get renderer-registry (node-type node))]
    (render-fn node level raw-body? opts)
    (throw (ex-info (str "Unsupported node type: " (node-type node)) {:node node}))))
(defn render-nodes
  [nodes level raw-body? opts]
  (str/join "\n" (interpose "" (map #(render-node % level raw-body? opts) nodes))))

(defn replace-between-markers
  [text region nodes raw-body? opts]
  (let [begin (str "// BEGIN " (name region))
        end (str "// END " (name region))
        pattern (re-pattern (str "(?sm)^([ \\t]*)" (Pattern/quote begin)
                                 ".*?^([ \\t]*)" (Pattern/quote end)))
        match (re-find pattern text)]
    (when-not match
      (throw (ex-info "Could not find markers in template"
                      {:region region})))
    (let [[whole bol] match
          rendered (when (seq nodes) (render-nodes nodes 2 raw-body? opts))]
      (str/replace-first
       text whole
       (str bol begin "\n"
            (when rendered (str rendered "\n"))
            bol end)))))

(defn generate-keymap
  [template config]
  (let [config (-> config
                   expand-aliases
                   resolve-left-bindings)
        layer-index-map (extract-layer-indexes config)
        opts {:layer-index-map layer-index-map}]
    (str/replace
     (reduce (fn [text [region {:keys [nodes raw-body?]}]]
               (replace-between-markers text region nodes raw-body? opts))
             template
             (:regions config))
     #"\n*\z" "\n")))

(defn load-config
  [path]
  (aero/read-config path))

(def cli-spec
  {:config {:require true :desc "Path to the EDN/Aero config"}
   :input  {:require true :desc "Path to the template .keymap"}
   :output {:desc "Output path (prints to stdout if omitted)"}})

(defn usage
  []
  (str "Usage: bb generator.clj --config <config.edn> --input <template.keymap> [--output <out.keymap>]\n\n"
       "Options:\n"
       (cli/format-opts {:spec cli-spec})))

(defn cli-error
  [{:keys [msg]}]
  (binding [*out* *err*]
    (println (str "Error: " msg "\n"))
    (println (usage)))
  (System/exit 1))

(defn write-output!
  [{:keys [config input output]}]
  (let [generated (generate-keymap (slurp input) (load-config config))]
    (if output
      (spit output generated)
      (print generated))
    generated))

(defn -main
  [& args]
  (if (some #{"--help" "-h"} args)
    (println (usage))
    (write-output! (cli/parse-opts args {:spec cli-spec
                                         :error-fn cli-error}))))

^:rct/test
(comment
  (render-behavior {:name "m" :type :mod-morph :bindings [:A :B] :mods "(MOD_LGUI)"} 0)
  ;=> "m: m {\n    compatible = \"zmk,behavior-mod-morph\";\n    #binding-cells = <0>;\n    bindings = <&kp A>, <&kp B>;\n    mods = <(MOD_LGUI)>;\n};"

  :rcf)

^:rct/test
(comment
  (bindings/compile-binding :P) ;=> "&kp P"

  (bindings/compile-binding :X) ;=> "&kp X"

  :rcf)

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
