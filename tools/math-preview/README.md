# Maths preview (Python reference of the app's maths engine)

`mathtex.py` is a line-for-line prototype of `study/MathTex.kt` (LaTeX subset -> boxes) and `richsplit.py` of
`study/RichSplit.kt` (answer text -> paragraphs, lists, inline and display maths, plain-text rescue). It was used to tune
the layout and to check behaviour, because the Kotlin could not be compiled where it was written.

- `python3 render.py` writes `sheet.png`: typeset examples (needs Pillow and DejaVu fonts). `preview.png` is a render.
- `python3 test_proto.py` fuzzes the parser/layout (20,000 random inputs), `python3 test_rich.py` prints the splitter.
- If you change a constant or rule in the Kotlin, change it here too and look at the picture first: it is much quicker.
- Server side: `node --experimental-strip-types supabase/functions/ai/mathjson.test.mjs` tests the JSON backslash repair.

## Page elements (0.30.0)

- `plotmath.py` mirrors `study/PlotMath.kt` (expression evaluator, number checker, `plot` spec, diagram spec and layout);
  `richblocks.py` mirrors the block parser in `study/RichSplit.kt` (headings, tables, callouts, fences, colours).
- `python3 test_plot.py` and `python3 test_blocks.py` are the checks; `python3 render_viz.py` draws a graph, a flow and a
  cycle to `viz_preview.png` (the Kotlin draws the same layout with Canvas).
