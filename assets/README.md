# Mandala Eclipse Live — source assets

These are INTERIM assets, cropped from a phone screenshot
(`~/workspace/user/files/1000001021_0_ecai.png`, 720x1604).

- `mandala_base.png` (560x745) — largest UI-free mandala region:
  original crop box (0, 525, 560, 1270). Below the icon rows, above the
  Gemini bottom sheet, left of the volume panel. Verified clean by eye.
- `eclipse_core.png` (380x380, RGBA) — eclipse disk + golden corona,
  center (270, 300) in base coords, radius 190 with smoothstep alpha
  falloff from r=140 to r=190.

## Swapping in the clean original

When Brandon supplies the original wallpaper file (no screenshot UI):

1. Drop it here as `mandala_base.png` (any size; renderer aspect-fills).
2. Re-extract the core: find the eclipse center (cx, cy) and corona radius R
   in the new file, then run:

   python3 - <<'EOF'
   from PIL import Image
   import math
   base = Image.open('assets/mandala_base.png').convert('RGBA')
   cx, cy, R_OUT, R_FULL = <cx>, <cy>, <R>, int(<R>*0.74)
   px = base.load(); w, h = base.size
   for y in range(max(0,cy-R_OUT), min(h,cy+R_OUT+1)):
       for x in range(max(0,cx-R_OUT), min(w,cx+R_OUT+1)):
           d = math.hypot(x-cx, y-cy)
           a = 0 if d >= R_OUT else (255 if d <= R_FULL
               else int(255*(1-(d-R_FULL)/(R_OUT-R_FULL))**2))
           r,g,b,_ = px[x,y]; px[x,y] = (r,g,b,a)
   base.crop((cx-R_OUT, cy-R_OUT, cx+R_OUT, cy+R_OUT)).save('assets/eclipse_core.png')
   EOF

3. Update `CORE_U`, `CORE_V`, `CORE_TEX_PX` in
   `android/app/src/main/java/com/apexforge/mandalawallpaper/MandalaRenderer.java`:
   CORE_U = cx / base_width, CORE_V = cy / base_height,
   CORE_TEX_PX = 2 * R_OUT. Also update the `texAspect` (560f/745f) in
   `onSurfaceChanged` to the new width/height.
4. Copy both PNGs into `android/app/src/main/assets/` and rebuild.
   (`cp assets/*.png android/app/src/main/assets/`)
