# Fonts

`pubspec.yaml` declares the Heebo family from this directory. The `.ttf` files
are **not committed** — Heebo ships under the SIL Open Font License, and
vendoring a font copy into an application repository is a licensing decision
for the operator, not something to inherit silently from a template.

Download Heebo from Google Fonts and place three files here:

    Heebo-Regular.ttf   (400)
    Heebo-Medium.ttf    (500)
    Heebo-Bold.ttf      (700)

Until then the app still renders correctly: `google_fonts` fetches Heebo at
runtime. That costs a network round trip on first launch, so bundling the
files is recommended for release builds.
