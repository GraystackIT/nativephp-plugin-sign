<?php

declare(strict_types=1);

namespace Graystack\NativeSign;

use Illuminate\Support\ServiceProvider;

/**
 * The signature pad needs no services of its own: NativePHP registers the element and its
 * Blade tag from nativephp.json, and the build bundles the Swift and Kotlin renderers.
 */
class NativeSignServiceProvider extends ServiceProvider {}
