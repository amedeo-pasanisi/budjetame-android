# Play Store listing icon

`play-store-icon-512.png` — the 512×512 PNG to upload to Play Console's
"App icon" slot.

- Full-bleed brand indigo (`#4F46E5`, the same `ic_launcher_background`)
  with the 💸 glyph centered at exactly the launcher icon's proportion
  (the 57 dp glyph cell of the 108 dp adaptive tile, scaled up), so the
  store listing shows the same icon users see on their launcher.
- 32-bit RGB, **no alpha channel** (Play Console rejects transparency).
- Rendered from the Noto Color Emoji 💸 (`emoji_u1f4b8.png`) — the font
  Android itself uses — the same source as the
  `mipmap-*/ic_launcher_foreground.png` launcher buckets.

To regenerate: rasterize the emoji at 456 px, center it on an 864×864
indigo canvas, downscale to 512, drop the alpha channel.
