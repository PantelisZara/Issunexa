# Supplied Issunexa branding

These original files are preserved as supplied. Runtime copies live in `../public/branding/` and are served by Vite at `/branding/`. Logos use their intrinsic proportions; no recoloring or redrawing is applied.

| Source file | Actual dimensions | Usage |
| --- | --- | --- |
| `issunexa-logo-dark.svg` | viewBox 299.553 × 69.1553 | Dark lettering; light-theme login, shell and README |
| `issunexa-logo-light.svg` | viewBox 297.147 × 68.5999 | White lettering; dark-theme login, shell and README |
| `issunexa-logo-light.png` | 4999 × 1154 | Dark lettering; retained raster source |
| `issunexa-logo-dark.png` | 4999 × 1154 | White lettering; retained raster source |
| `issunexa-icon.svg` | viewBox 15.5117 × 15.5117 | Compact header and primary favicon |
| `issunexa-icon-192.png` | 193 × 193 | PNG favicon fallback and web-manifest icon |
| `issunexa-icon-512.png` | 513 × 513 | Web-manifest icon |
| `apple-touch-icon.png` | 181 × 181 | Apple touch icon |
| `favicon.ico` | Embedded bitmap 1536 × 1536 | Retained source; unused in the browser |

The supplied SVG and PNG wordmark filenames use opposite light/dark conventions. The dark-lettering SVG (`#0C1A2E`) is used on light surfaces and the white-lettering SVG on dark surfaces. The accent (`#7D43E7`) comes from the supplied X mark. Both SVGs are served unchanged; the README selects its variant through a `prefers-color-scheme` picture.

The raster app icons are one pixel larger than their nominal filenames. HTML and the web manifest declare their actual dimensions. The supplied ICO is approximately 2.5 MiB and advertises a different size from its embedded bitmap, so the application uses the supplied SVG favicon and PNG fallback instead.

`site.webmanifest` provides product identity and icons only; the application has no service worker or offline support.
