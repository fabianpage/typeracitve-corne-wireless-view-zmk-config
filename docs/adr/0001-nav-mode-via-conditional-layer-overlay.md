# Nav mode via conditional-layer overlay

To make the four navigation cells switch between arrow keycodes and vim `h j k l`
under a persistent global toggle, we express **Nav mode** as a `Vim` flag layer
(index 5, all `&trans`) plus a `NavVim` overlay layer (index 6). A conditional
layer wires `if-layers <1 5> → then-layer <6>`, so hjkl only appears when `Nav1`
is held **and** the `Vim` flag is on — it never hijacks `BASE`'s `S A E N`.

We chose conditional-layers over a `mod-morph` because a mod-morph can only read
modifiers, not an arbitrary global flag. Conditional-layers is the ZMK-idiomatic
way to express "a global mode flag that only takes effect inside another layer,"
and the repo already uses the pattern (`if-layers <1 2> → then-layer <4>` for BT).
The toggle itself is `&tog 5` on `Nav1`'s outer-column top cell.
