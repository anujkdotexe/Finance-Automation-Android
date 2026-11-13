# Finance Automation logo and icon kit

Concept: one rupee glyph whose tail turns into a check mark, meaning "the expense is logged". Flat shapes, no gradients, no text. Teal background `#006874`, white glyph, light cyan `#72D6E4` for the check arm.

## Files
- `logo_full.svg`: the logo as a rounded square (use for Play Store art, website, README).
- `logo_background.svg`, `logo_foreground.svg`, `logo_monochrome.svg`: the three adaptive icon layers on a 108 by 108 canvas. The whole glyph sits inside the 66 unit safe circle (largest distance from the centre including stroke is about 32.7, limit 33).
- `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`: the same layers as Android vector drawables, ready to copy to `res/drawable`. The background layer is the colour `#006874`.
- `previews/logo_preview_sheet.png`: the icon in circle, squircle and rounded masks, at 48, 32 and 24 px, themed (monochrome) light and dark, notification size, and the safe circle check.
- `previews/logo_512.png`: 512 by 512 preview (Play Store icon source; export a final PNG from `logo_full.svg`).

## How to use in the app (UI phase)
1. Adaptive icon: `res/mipmap-anydpi-v26/ic_launcher.xml` with `<background android:drawable="@color/ic_launcher_background"/>`, `<foreground android:drawable="@drawable/ic_launcher_foreground"/>`, `<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>`.
2. Notification small icon, Quick Settings tile and widget glyph: use `ic_launcher_monochrome.xml` (single colour; the system tints it). For the widget, add a small plus badge in the corner as a separate drawable.
3. Keep every file here free of emojis and local paths.
