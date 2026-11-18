# climber-port

Python (Pillow) port of `intro/ClimberRig.kt` and `intro/ClimberShapes.kt`, for looking at the climbers where there is no
Kotlin compiler (`tools/scene-preview` needs kotlinc). It is a line-for-line copy: **when you change the Kotlin, change
this too (or the reverse), or the pictures lie.**

    python3 render.py base      # sheet_base.png: walk, walk, other climber, stand, cheer, tired
    python3 heads.py heads.png  # four head close-ups
    python3 strip.py            # cheer and wave filmstrips (needs strip names in the script)
    python3 nudge.py out.png    # the nudge: both climbers over the mountain (needs mountain.png copied beside it from res/drawable-nodpi)

`sky.py` is the skyline from `Geo.kt` (`SKY`). Needs `pillow`.
