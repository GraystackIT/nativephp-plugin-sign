<?php

declare(strict_types=1);

namespace Graystack\NativeSign\Components;

use Native\Mobile\Edge\Components\Native\NativeBladeComponent;

/**
 * `<native:signature-pad />`
 */
class SignaturePad extends NativeBladeComponent
{
    protected bool $isSelfClosing = true;

    protected function elementType(): string
    {
        return 'signature_pad';
    }
}
