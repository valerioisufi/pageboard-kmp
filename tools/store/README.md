# The store images of PencilNotes

The scripts that made the listing of `pencilnotes/androidApp/fastlane/metadata/android/` and the
images of `docs/images/`. How they are used: `docs/plans/store-listing.md`.

* `slides.py <out.pdf>`: the demo slides, a PDF with a real text layer.
* `strokes.py <out dir> <shape> <args...>`: strokes as touchscreen events of an emulator
  (`/dev/input/event1`, 2560 x 1600). Push the directory to `/data/local/tmp/st` and run its
  `run.sh` with `adb root`: each point is written on its own, as a hand would.
* `graphics.py <shots dir> <repo dir>`: the captioned screenshots, the feature graphic, the icon,
  the social preview of GitHub and the images of the README, from the raw screenshots
  (`tablet-1-annotate.png` ... `phone-3-ask.png`).

Run them with `python -I` (Pillow is the only dependency).
