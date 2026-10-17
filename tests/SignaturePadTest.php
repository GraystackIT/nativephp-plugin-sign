<?php

declare(strict_types=1);

use Graystack\NativeSign\Elements\SignaturePad;
use Native\Mobile\Edge\CallbackRegistry;

it('serializes the fluent props', function (): void {
    $registry = new CallbackRegistry;

    $node = SignaturePad::make()
        ->value('data:image/png;base64,AAAA')
        ->strokeColor('#14272A')
        ->lineColor('#8E8E93')
        ->exportColor('#000000')
        ->accentColor('#3F8F92')
        ->strokeWidth(2.5)
        ->placeholder('Sign here')
        ->clearLabel('Clear')
        ->allowRotation()
        ->a11yLabel('Signature')
        ->onChange('signatureChanged')
        ->toArray($registry);

    expect($node['type'])->toBe('signature_pad')
        ->and($node['props'])->toMatchArray([
            'value' => 'data:image/png;base64,AAAA',
            'stroke_color' => '#14272A',
            'line_color' => '#8E8E93',
            'export_color' => '#000000',
            'accent_color' => '#3F8F92',
            'stroke_width' => 2.5,
            'placeholder' => 'Sign here',
            'clear_label' => 'Clear',
            'allow_rotation' => true,
            'a11y_label' => 'Signature',
        ])
        ->and($node['props']['on_change'])->toBeInt()
        ->and($registry->resolve($node['props']['on_change']))->not->toBeNull();
});

it('reads the Blade attributes in both spellings', function (array $attributes): void {
    $pad = SignaturePad::make();
    $pad->applyAttributes($attributes);

    expect($pad->toArray(new CallbackRegistry)['props'])->toMatchArray([
        'value' => '',
        'stroke_color' => '#14272A',
        'stroke_width' => 4.0,
        'clear_label' => 'Löschen',
        'disabled' => true,
    ]);
})->with([
    'kebab-case' => [['value' => '', 'stroke-color' => '#14272A', 'stroke-width' => '4', 'clear-label' => 'Löschen', 'disabled' => true]],
    'camelCase' => [['value' => '', 'strokeColor' => '#14272A', 'strokeWidth' => '4', 'clearLabel' => 'Löschen', 'disabled' => true]],
]);

it('leaves out what was not given', function (): void {
    expect(SignaturePad::make()->toArray(new CallbackRegistry)['props'] ?? [])
        ->not->toHaveKeys(['stroke_color', 'placeholder', 'on_change', 'disabled']);
});

it('declares the signature pad in its manifest', function (): void {
    $manifest = json_decode((string) file_get_contents(dirname(__DIR__).'/nativephp.json'), true, flags: JSON_THROW_ON_ERROR);
    $component = $manifest['components'][0];

    expect($manifest['namespace'])->toBe('NativeSign')
        ->and($component['type'])->toBe('signature_pad')
        ->and(class_exists($component['element']))->toBeTrue()
        // The Blade component needs Laravel's view layer, which only the app has
        ->and(is_file(dirname(__DIR__).'/src/Components/SignaturePad.php'))->toBeTrue()
        ->and(is_file(dirname(__DIR__).'/resources/ios/'.$component['ios_renderer'].'.swift'))->toBeTrue()
        ->and(is_file(dirname(__DIR__).'/resources/android/SignaturePadRenderer.kt'))->toBeTrue()
        ->and($manifest['ios']['min_version'])->not->toBeEmpty()
        ->and($manifest['ios']['init_function'])->toBe('registerNativeSignOrientation')
        ->and(file_get_contents(dirname(__DIR__).'/resources/ios/NativeSignOrientation.swift'))->toContain('func registerNativeSignOrientation()')
        ->and($manifest['android']['min_version'])->toBeInt();
});

it('resolves colours from the palette and CSS hex with alpha', function (): void {
    $props = SignaturePad::make()
        ->strokeColor('red-500')
        ->lineColor('#8B5CF680')
        ->exportColor('#000000')
        ->toArray(new CallbackRegistry)['props'];

    expect($props['stroke_color'])->toStartWith('#')->not->toBe('red-500')
        ->and($props['line_color'])->toBe('#808B5CF6')
        ->and($props['export_color'])->toBe('#000000');
});
