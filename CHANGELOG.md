# Changelog

## 0.1.0

- `<native:signature-pad>`: sign with a finger on iOS (SwiftUI) and Android (Jetpack Compose); the drawing comes back to PHP as a transparent, cropped PNG data URL after every stroke.
- `allow-rotation`: let the screen turn while the pad is shown; on iOS the app opts in to portrait everywhere else with `NativeSignPortraitOutsidePads`.
- Colours in NativePHP's colour grammar, `accent-color` for the clear button, `a11y-label` / `a11y-hint`.
