# TypingAnimation

A runtime-toggleable module in **Visual**. It defaults to off. Style selects Fade,
Slide, or Pop; Speed is the duration in milliseconds (50–250). Smooth Caret also
eases selection highlighting. Chat, Signs and Books, and Other Fields independently
control input coverage. Settings use the existing config persistence and ClickGUI.

## Verified 1.21.11 targets

The project uses `loom.officialMojangMappings()`. These targets were checked against
Loom's generated 1.21.11 sources and compiled bytecode:

| Target | Hook | Covered input |
| --- | --- | --- |
| `EditBox.renderWidget` | Two formatted text draw operations, caret operations, selection | Chat/commands; anvil; creative search; server name/address; world name/seed; recipe search; debug/options search fields; command/structure blocks; book signing title; mod widgets using this render method |
| `MultiLineEditBox.renderContents` | Three page text draw operations, caret operations, selection | Writable book pages (1.21.11 uses this widget), plus other vanilla multiline inputs |
| `AbstractSignEditScreen.renderSignText` | Sign line text draw, caret operations, selection | Standing/wall and hanging signs, front/back editing |
| `MultilineTextField.StringView` | Read-only accessor to `beginIndex` | Maps wrapped book lines back to their real input offsets; the record is package private |

All render hooks use `@WrapOperation` and small HEAD/TAIL injections, with remapped
class/method references. There are no overwrites, text setters, input-event changes,
packet changes, or global font/draw hooks. Labels, placeholders, completion
suggestions, page numbers, incoming chat, and other output remain vanilla.

## State and rendering

`TextAnimator` owns all animation behavior and a weak map keyed by widget/screen
instance; sign screens have four independent line states. Values never reference
their weak keys. First observation establishes a settled baseline. A common
prefix/suffix diff preserves surviving character timestamps and records recently
rendered deleted glyphs for an 85 ms fade. Ghost buffers grow only when an edit requires more capacity; records expire promptly.

Insertions start immediately, without a stagger or queue. More than three Unicode
code points inserted together share a single fade block, regardless of style.
Deletion snapshots preserve the current opacity, slide position, and pop scale.
Rapid typing reduces duration to keep old characters settled, with an absolute
250 ms setting cap. Surrogate pairs stay together across replacements.

Styled glyph sequences retain the draw call's font, formatting, ARGB color, shadow,
and visible-text origin. Glyph positions use font advances and vanilla scrolling
and line offsets. This preserves resource-pack fonts and custom style fonts,
including Xuong when supplied by the active font configuration. This checkout
contains no separate Xuong renderer or font assets.

Glyphs, timestamps, and removal records are cached; the animator creates no strings
or lists in its per-frame loops. Buffer replacements occur on text edits. Settled
runs retain the original batched draw. Minecraft's own GUI submission still creates
its normal render-state objects. The caret uses frame-time exponential smoothing
and a continuous cosine blink; selection uses an eased scissor reveal while keeping
vanilla selection colors and inverted text.

## Deliberate vanilla fallbacks

No requested vanilla screen is omitted. Bidirectional font mode retains vanilla
rendering so text shaping and cursor direction stay correct. Password/masked widget
names or labels are excluded; formatted glyph substitutions also fall back before
any animated draw and discard removal ghosts. Custom mod subclasses that replace
the vanilla render method entirely are outside these hooks and retain their own
rendering. Arbitrary third-party custom renderers have not been tested.

## Validation

`gradle build` compiles and remaps the mod and runs the JUnit suite.
`TextAnimatorTest` checks initial values, stable-frame buffer reuse, middle edits,
suffix timestamp retention, paste grouping, deletion, fast typing, and emoji
replacement boundaries. Runtime smoke testing uses the actual Fabric client and
Minecraft font/GUI renderer under a virtual display; temporary smoke tooling is
kept outside the shipped mod. All 104 tests passed. The runtime smoke passed
for single-line and multiline fields, standing/hanging signs, all styles, scrolling,
formatting, masked fallback, multi-line selections, runtime toggling, and deletion
opacity/position/expiry (including 200 removed glyphs).
