# Maths preview (Python reference of the app's maths engine)

`mathtex.py` is a line-for-line prototype of `study/MathTex.kt` (LaTeX subset -> boxes) and `richsplit.py` of
`study/RichSplit.kt` (answer text -> paragraphs, lists, inline and display maths, plain-text rescue). It was used to tune
the layout and to check behaviour, because the Kotlin could not be compiled where it was written.

- `python3 render.py` writes `sheet.png`: typeset examples (needs Pillow and DejaVu fonts). `preview.png` is a render.
- `python3 test_proto.py` fuzzes the parser/layout (20,000 random inputs), `python3 test_rich.py` prints the splitter.
- If you change a constant or rule in the Kotlin, change it here too and look at the picture first: it is much quicker.
- Server side: `node --experimental-strip-types supabase/functions/ai/mathjson.test.mjs` tests the JSON backslash repair.
