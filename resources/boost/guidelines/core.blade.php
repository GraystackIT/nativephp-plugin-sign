## graystack/nativephp-sign

A native signature pad for NativePHP Mobile v4 (SwiftUI on iOS, Jetpack Compose on Android).

- Render it with `<native:signature-pad native:model="signature" class="w-full h-48 rounded-xl bg-theme-surface" />`.
- The bound property receives the whole drawing as a transparent PNG data URL (`data:image/png;base64,…`) after every stroke, and `''` once the pad is cleared. `@change="method"` receives the same string.
- The PNG is drawn in `export-color` (black by default), not in the on-screen `stroke-color`, so it prints in dark mode too.
- Size and background come from classes; colours take theme tokens: `:stroke-color="theme('on-surface')"`.
- Other attributes: `placeholder`, `clear-label`, `stroke-width`, `line-color`, `accent-color`, `disabled`, `a11y-label`.
- `allow-rotation` lets the screen turn to landscape while the pad is shown. On iOS, enable landscape in `nativephp.orientation.iphone` and set `'NativeSignPortraitOutsidePads' => true` in `nativephp.permissions` to keep every other screen in portrait.
- Give the pad a stable `native:key`; the exported PNG is cropped to the drawing and capped at 1200 px wide.
- To clear it from PHP, empty the property and change the element's `native:key`.
- Decode the image with `base64_decode(substr($dataUrl, strlen('data:image/png;base64,')))`.
- Native code compiles in at build time: after installing or updating, the app needs `php artisan native:run`.
