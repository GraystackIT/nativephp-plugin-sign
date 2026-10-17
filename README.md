# NativePHP Sign

A native signature pad for [NativePHP Mobile](https://nativephp.com/mobile) v4. The user signs with a finger. The pad is drawn with SwiftUI on iOS and Jetpack Compose on Android. After every stroke, PHP receives the drawing as a transparent PNG data URL.

```blade
<native:signature-pad
    native:model="signature"
    class="w-full h-48 rounded-xl bg-theme-surface"
    :placeholder="__('Sign here')"
    :clear-label="__('Clear')"
    :stroke-color="theme('on-surface')"
/>
```

```php
public string $signature = ''; // "data:image/png;base64,iVBORw0KGgo…" once signed

public function save(): void
{
    $png = base64_decode(substr($this->signature, strlen('data:image/png;base64,')));
    // store it, send it to your API, …
}
```

## Installation

The package is not on Packagist yet, so add the repository to your app's `composer.json` first:

```json
"repositories": [
    {
        "type": "vcs",
        "url": "https://github.com/graystackit/nativephp-plugin-sign"
    }
]
```

```bash
composer require graystack/nativephp-sign:^0.1
php artisan vendor:publish --tag=nativephp-plugins-provider   # once, before the first plugin
php artisan native:plugin:register graystack/nativephp-sign
php artisan native:plugin:list                                # verify it is registered
```

The Swift and Kotlin renderers are compiled into the app at build time. Rebuild after installing:

```bash
php artisan native:run ios      # or android
```

## Usage

### Binding

`native:model="signature"` keeps a public property in sync with the pad.

- After every stroke, the property holds the whole drawing as `data:image/png;base64,…`.
- After the pad is cleared, the property holds an empty string.

To call a method instead, use `@change`:

```blade
<native:signature-pad @change="signatureChanged" class="w-full h-48" />
```

```php
public function signatureChanged(string $dataUrl): void
{
    $this->signature = $dataUrl;
}
```

While the pad has no strokes of its own, it shows the bound value. A signature therefore survives the screen being rebuilt. Strokes drawn on a shown signature add to it; only the clear button starts over.

To clear the pad from PHP, empty the property and give the element a new `native:key`.

Give the pad a stable `native:key` anyway. Without one, its identity is its position, and content inserted above it (a validation message, say) resets what was drawn.

`native:model` always syncs after every stroke. The `.blur` and `.debounce` modifiers have no effect on the pad.

### Attributes

| Attribute | Default | |
|---|---|---|
| `native:model` / `value` | `''` | The current drawing as a PNG data URL. |
| `@change` | | Called with the data URL after every stroke, and with `''` when the pad is cleared. |
| `stroke-color` | `#1C1C1E` | Stroke colour on screen. |
| `stroke-width` | `3` | Stroke width in points (dp on Android). |
| `line-color` | grey | Colour of the signature line and the placeholder. |
| `export-color` | `#000000` | Stroke colour in the exported PNG. |
| `accent-color` | platform accent | Colour of the clear button. |
| `placeholder` | | Text shown on the empty pad. |
| `clear-label` | `Clear` | Label of the clear button, shown once something is drawn. |
| `allow-rotation` | `false` | Let the screen turn to landscape while the pad is shown, see below. |
| `disabled` | `false` | Shows the pad but does not accept drawing. |
| `a11y-label`, `a11y-hint` | | Accessibility label and hint. |

Size, corners and background come from the element's classes, for example `w-full h-48 rounded-xl bg-theme-surface`.

Colours accept NativePHP's colour grammar: hex values (`#14272A`, CSS order with alpha `#8B5CF680`), Tailwind palette names with optional opacity (`red-500`, `red-500/40`) and `theme('…')` tokens.

### Turning the screen

`allow-rotation` lets the screen turn while the pad is shown, so it can be signed in landscape with more room. This also works in an app that is otherwise portrait only. When the pad goes, the screen turns back to portrait.

```blade
<native:signature-pad allow-rotation native:model="signature" class="w-full flex-1 rounded-xl bg-theme-surface" />
```

- **iOS:** Landscape has to be among the app's orientations, and the app has to ask the plugin to keep its other screens in portrait:

  ```php
  // config/nativephp.php
  'orientation' => ['iphone' => ['portrait' => true, 'upside_down' => false, 'landscape_left' => true, 'landscape_right' => true]],
  'permissions' => [
      // ...
      'NativeSignPortraitOutsidePads' => true, // written to Info.plist
  ],
  ```

  With the key set, every screen on iPhone stays in portrait unless a pad with `allow-rotation` is on it. Without it, the plugin leaves the app's orientations alone, so installing it never changes how an app turns. iPad is never narrowed.
- **Android:** No configuration is needed. The activity follows the sensor (never upside down) while such a pad is shown, and gets its previous orientation back once the last one goes.

A drawing made before turning is kept as long as it is bound with `native:model` or `value`. It is shown at its own size, scaled down only where it no longer fits. Further strokes add to it.

### The PNG

- The background is transparent.
- The stroke is drawn in `export-color`, not in the on-screen `stroke-color`. A signature given in dark mode, with a light stroke on screen, still prints dark on paper.
- The image is cropped to what was drawn, plus a small margin.
- iOS exports at twice the size in points. Android exports at the pixel size. Both scale down to at most 1200 px wide.
- A typical signature is 5–30 KB. Keep pads at a sensible size: the value is sent back to the device as a prop, and very large drawings can exceed what a prop carries (about 64 KB).

### Fluent API

```php
use Graystack\NativeSign\Elements\SignaturePad;

SignaturePad::make()
    ->placeholder('Sign here')
    ->strokeColor(theme('on-surface'))
    ->onChange('signatureChanged');
```

## Behaviour

- **Scroll views:** Drawing inside a scroll view draws instead of scrolling the page.
- **Taps:** A tap leaves a dot.
- **Bridge functions, events, permissions:** The plugin has no bridge functions, no events and needs no permissions. It is a native UI element used from Blade, so there is no JavaScript library.
- **iOS start-up:** Only when the app sets `NativeSignPortraitOutsidePads`, the plugin's init function narrows the orientations of the app's view controllers to keep the other screens in portrait.

## Requirements

- PHP 8.3, NativePHP Mobile `^4.6`
- iOS 17 or later, Android 8 (API 26) or later

## Testing

```bash
composer test
```

## License

MIT, see [LICENSE](LICENSE).
