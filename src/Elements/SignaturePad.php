<?php

declare(strict_types=1);

namespace Graystack\NativeSign\Elements;

use Native\Mobile\Edge\CallbackRegistry;
use Native\Mobile\Edge\Element;
use Native\Mobile\Edge\TailwindParser;

/**
 * A pad to sign on with a finger, drawn natively (SwiftUI on iOS, Jetpack Compose on Android).
 *
 * After every stroke the pad reports the whole drawing as a PNG data URL
 * (`data:image/png;base64,…`) through `@change` — or `native:model`, which binds it to a
 * property like any input. Clearing the pad reports an empty string. The exported PNG has a
 * transparent background and is drawn in `export-color` (black by default), whatever colour the
 * stroke has on screen, so a signature made in dark mode still prints.
 *
 * Size and background come from the classes (`class="w-full h-48 rounded-xl bg-theme-surface"`).
 * To start over from PHP, give the element a new `native:key`. With `allow-rotation`, the screen
 * may turn to landscape while the pad is shown.
 */
class SignaturePad extends Element
{
    protected string $type = 'signature_pad';

    /** @var array<string, mixed> */
    protected array $componentProps = [];

    protected ?string $changeCallback = null;

    public static function make(): static
    {
        return new static;
    }

    /**
     * @param  array<string, mixed>  $attrs
     */
    public function applyAttributes(array $attrs): void
    {
        if (array_key_exists('value', $attrs)) {
            $this->value((string) $attrs['value']);
        }

        foreach ([
            'stroke-color' => 'strokeColor',
            'strokeColor' => 'strokeColor',
            'line-color' => 'lineColor',
            'lineColor' => 'lineColor',
            'export-color' => 'exportColor',
            'exportColor' => 'exportColor',
            'accent-color' => 'accentColor',
            'accentColor' => 'accentColor',
            'placeholder' => 'placeholder',
            'clear-label' => 'clearLabel',
            'clearLabel' => 'clearLabel',
        ] as $attribute => $setter) {
            if (isset($attrs[$attribute]) && $attrs[$attribute] !== '') {
                $this->{$setter}((string) $attrs[$attribute]);
            }
        }

        if (isset($attrs['stroke-width']) || isset($attrs['strokeWidth'])) {
            $this->strokeWidth((float) ($attrs['stroke-width'] ?? $attrs['strokeWidth']));
        }

        if (! empty($attrs['disabled'])) {
            $this->disabled();
        }

        if (! empty($attrs['allow-rotation']) || ! empty($attrs['allowRotation'])) {
            $this->allowRotation();
        }

        $this->applyA11yAttributes($attrs);
    }

    /**
     * The current drawing as a PNG data URL, shown while the pad has no strokes of its own — so
     * a signature survives the screen being rebuilt. Empty for a blank pad.
     */
    public function value(string $dataUrl): static
    {
        $this->componentProps['value'] = $dataUrl;

        return $this;
    }

    /**
     * Colour of the stroke on screen, e.g. `theme('on-surface')`.
     */
    public function strokeColor(string $color): static
    {
        $this->componentProps['stroke_color'] = self::color($color);

        return $this;
    }

    /**
     * Colour of the signature line and the placeholder.
     */
    public function lineColor(string $color): static
    {
        $this->componentProps['line_color'] = self::color($color);

        return $this;
    }

    /**
     * Colour of the stroke in the exported PNG; black by default.
     */
    public function exportColor(string $color): static
    {
        $this->componentProps['export_color'] = self::color($color);

        return $this;
    }

    /**
     * Colour of the clear button; the platform's accent colour by default.
     */
    public function accentColor(string $color): static
    {
        $this->componentProps['accent_color'] = self::color($color);

        return $this;
    }

    /**
     * Stroke width in points (dp on Android); 3 by default.
     */
    public function strokeWidth(float $width): static
    {
        $this->componentProps['stroke_width'] = $width;

        return $this;
    }

    /**
     * Text shown on the empty pad, e.g. "Sign here".
     */
    public function placeholder(string $text): static
    {
        $this->componentProps['placeholder'] = $text;

        return $this;
    }

    /**
     * Label of the button that clears the pad; "Clear" by default.
     */
    public function clearLabel(string $text): static
    {
        $this->componentProps['clear_label'] = $text;

        return $this;
    }

    /**
     * Let the screen turn to landscape while the pad is shown, even in an app that is otherwise
     * portrait only — for more room to sign. iOS needs landscape among the app's orientations
     * (`nativephp.orientation.iphone`); the plugin keeps every other screen in portrait.
     */
    public function allowRotation(bool $allow = true): static
    {
        $this->componentProps['allow_rotation'] = $allow;

        return $this;
    }

    public function disabled(bool $disabled = true): static
    {
        $this->componentProps['disabled'] = $disabled;

        return $this;
    }

    /**
     * The component method called with the PNG data URL after every stroke ('' when cleared).
     */
    public function onChange(string $method): static
    {
        $this->changeCallback = $method;

        return $this;
    }

    /**
     * A colour in NativePHP's colour grammar — palette names (`red-500`), opacity modifiers
     * (`red-500/40`), CSS hex with alpha (`#8B5CF680`) — as the hex the native renderers read.
     * Anything unrecognized passes through for the native side's own fallback.
     */
    private static function color(string $value): string
    {
        if (! method_exists(TailwindParser::class, 'resolveColorValue')) {
            return $value;
        }

        return TailwindParser::resolveColorValue($value) ?? $value;
    }

    protected function resolveProps(CallbackRegistry $registry): array
    {
        $props = $this->componentProps;

        if ($this->changeCallback !== null) {
            $props['on_change'] = $registry->register($this->changeCallback);
        }

        return $props;
    }
}
