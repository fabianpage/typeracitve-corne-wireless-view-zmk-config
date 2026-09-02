# Domain Model — ZMK-TOTEMIST Generator

## Glossary

- **Binding cell** — A single key action or combo action. Can be a keyword (`:P`, `:trans`, `:none`) or a vector (`[:lt 3 :DE_S]`). Resolved through the global alias map before rendering or assembly.
- **Binding grid** — A 2D vector of binding cells. Rows may have varying widths (e.g. a split layout row vs a combo-layer grid).
- **Layer** — A node in the `:keymap` region. Renders as a ZMK `keymap` layer node with `display-name` and `bindings`.
- **Combo-layer** — A node in the `:combos` region. Defines a grid of combo trigger cells plus a `:pattern` (relative offsets) and `:row-widths`. Renders into multiple ZMK combo nodes.
- **Alias** — A keyword mapping in the config (e.g. `{:S [:lt 3 :DE_S]}`). Aliases are resolved globally before any rendering or assembly.
- **Tile** — A reusable named binding grid. Defined in the top-level `:tiles` map. Does not render directly; exists only in the config data structure.
- **Physical-half Tile** — A Tile representing one hand-side region of the keyboard layout, used to make the physical shape of a Layer or Combo-layer explicit without requiring symmetry.
- **Placement** — A tile referenced at a position within a larger grid, with optional mirroring. `{:tile :name :pos [col row] :mirror :horizontal}`.
- **Behavior** — A named, typed ZMK behavior node. Defined as a map with `:name` (DT node id), `:type` (keyword, looked up in the generator's behavior-type registry), optional `:label` (display-name; omitted if absent), `:bindings` (binding DSL forms), and type-specific pass-through keys (e.g. `:mods`). Macros are a subclass of Behavior whose `compatible` string is `zmk,behavior-macro` (or a param variant).
- **Assembled grid** — The flat binding grid produced by merging placements into a container with given `:row-widths` and an `:empty` fill cell.
- **Nav mode** — Which keycodes the four navigation cells emit while the nav layer (`Nav1`) is held: either **Arrow** (arrow keycodes) or **Vim** (the literal letters `h j k l`). It is a global toggle that persists independently of layer entry — flipping it survives `:sl`/holding the nav layer. Implemented as the `Vim` flag layer (index 5, all `&trans`) plus a `NavVim` overlay (index 6) activated by a conditional layer when both `Nav1` and `Vim` are on.
  _Avoid_: hjkl mode, arrow toggle.

## Decisions

- Alias expansion is global and runs **before** tile assembly and rendering.
- Tiles live in a separate namespace from Layers and Combo-layers (top-level `:tiles` key).
- A Tile is **only** a binding grid. Tiles may be used by Layers and Combo-layers, but a Tile cannot reference a Combo-layer.
- Tiles may reference other Tiles recursively (composition), but cycle detection is required.
- `:mirror` supports `:horizontal` only for now (reverse column order within each row).
- Overlapping placements: **last placement wins**.
- Empty cells in an assembled grid are filled with the container's `:empty` cell (default `:trans`).
- A node (Layer or Combo-layer) may specify either inline `:bindings` **or** `:placements` — never both. Specifying both is an error.
- The `:clip` flag on a Placement controls whether out-of-bounds cells are silently dropped. If absent, out-of-bounds cells are an error.
- **Behavior ontology**: Macros and other user-definable behaviors are unified under a single "Behavior" concept in the generator's domain model. The registry maps `:type` keywords to ZMK `compatible` strings, `#binding-cells`, and binding emission strategies. However, the config still declares separate `:macros` and `:behaviors` region entries because ZMK's DT parser requires them in separate `macros { }` and `behaviors { }` parent blocks. The split is an output artifact, not a domain distinction.
- **Optional label**: A behavior (including macro subclass) that omits `:label` renders without the `name: label` DT syntax (`name {`). When `:label` is present and differs from `:name`, it is emitted as `name: label {`. This applies uniformly to all named nodes.
- **Behavior map ordering**: Behavior nodes declared in a map (e.g. `:behaviors`) are sorted by their map key (node name) before rendering, ensuring deterministic output.
