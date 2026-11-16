#!/bin/bash
# Draws the home mountain scene on a desktop with the app's own plain-Kotlin scene code (rig, shapes, SceneFx),
# so poses, weather and moods can be checked without a phone. Needs kotlinc and java.
#   ./run.sh Full.kt     -> out/full_*.png (day, dawn, dusk, night, snow, summit)
#   LIGHT=1 ./run.sh Full.kt -> out/light_*.png, the same on the light theme (needs mountain_light.png: python3 make_light_mountain.py)
#   ./run.sh Zoom.kt     -> out/z_*.png    (each mood up close)
#   ./run.sh Anim.kt     -> out/anim/f*.png (a 16 s story; ffmpeg -framerate 20 -i out/anim/f%03d.png preview.mp4)
cd "$(dirname "$0")"
I=../../app/src/main/java/com/godbless/lockin/intro
mkdir -p out/anim
M=$1; K=$(basename "$M" .kt)
kotlinc $I/Geo.kt $I/Ground.kt $I/Easing.kt $I/ClimberRig.kt $I/ClimberShapes.kt $I/SceneFx.kt Consts.kt Render.kt Fx.kt Base.kt "$M" -include-runtime -d "out/$K.jar" 2>&1 | grep -i error
java ${LIGHT:+-Dlight=1} -cp "out/$K.jar" "com.godbless.lockin.intro.${K}Kt"
