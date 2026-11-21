# Lucide icons

Official upstream: https://github.com/lucide-icons/lucide

Pinned revision: `6657faa96ae10efe1509203c62b60d913006ab82`.

`icons/` contains 28 original SVG files. `manifest.json` records their SHA-256 hashes. The full upstream LICENSE (including the Feather-derived icons notice) is retained here and in the APK at `assets/licenses/lucide.txt`.

`scripts/import-lucide.py` reproducibly converts the SVG paths and basic shapes into native Android VectorDrawable XML files. Stroke width, round line caps and round joins follow the original icons. NYQOVA additionally derives filled heart and circle variants from those same source paths for favorite state and color selection. No font or raster image is required at runtime.

Regenerate using Python 3.8 or later: `python scripts/import-lucide.py`.
