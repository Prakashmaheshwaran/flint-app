#!/usr/bin/env python3
"""Physical-tap regression for Today → picker → Add, Cancel and system Back.

Requires a freshly installed debug APK, Chrome, completed onboarding, and no
active timer on a disposable emulator. Run under the shared device lock. No
session is started or rule saved. Environment: ADB, ANDROID_SERIAL,
FLINT_UI_EVIDENCE (default /tmp/flint-control-insets-proof).
"""
import json
import re
from native_ui import Ui, adb, SERIAL, PACKAGE


def picker_case(ui, name, keyboard):
    ui.prefix = name
    ui.launch()
    ui.tap('Start focus')
    ui.find('Choose what rests')
    ui.tap('Choose what rests')
    if keyboard:
        ui.tap('Search apps')
        ui.text('Chrome')
    ui.find('Chrome')
    ui.tap('Chrome')
    if ui.nodes('What rests'):
        ui.node('1 SELECTED')
    ui.check_controls('Add · 1', 'Cancel')
    if keyboard:
        assert 'mInputShown=true' in adb('shell', 'dumpsys', 'input_method').decode()
    ui.capture('selected')
    ui.tap('Add · 1')  # Center of the actual touch target; not a semantic click.
    ui.node('Start focus')
    ui.node('Block 1 app')
    ui.node('Chrome')
    ui.capture('confirmed')
    if not keyboard:
        # Both exits discard a pending deselection, retaining the confirmed draft.
        for exit_label in ('Cancel', 'BACK'):
            ui.tap('Block 1 app')
            ui.find('Chrome')
            ui.tap('Chrome')
            if exit_label == 'BACK':
                ui.key()
            else:
                ui.tap(exit_label)
            ui.node('Block 1 app')
            ui.node('Chrome')
    ui.tap('Close')
    # Observe Today itself. A selected tab is not necessarily exposed as clickable.
    ui.node('YOUR NEXT SESSION')
    print(json.dumps({'case': name, 'passed': True}), flush=True)


def main():
    assert SERIAL.startswith('emulator-'), 'Use a disposable emulator'
    original_font = adb('shell','settings','get','system','font_scale').decode().strip()
    original_size = adb('shell','wm','size').decode()
    original_ime = adb('shell','settings','get','secure','show_ime_with_hard_keyboard').decode().strip()
    override = re.search(r'Override size: (\d+x\d+)', original_size)
    ui = Ui()
    try:
        adb('shell','settings','put','secure','show_ime_with_hard_keyboard','1')
        # At the audit emulator's 420 dpi, 840×1800 is a 320×686 dp viewport.
        for name, scale, size in (('normal','1.0','reset'), ('large','1.5','reset'),
                                  ('narrow-large','1.5','840x1800')):
            adb('shell','wm','size',size)
            adb('shell','settings','put','system','font_scale',scale)
            for keyboard in (False, True):
                case = name + ('-ime' if keyboard else '')
                try:
                    picker_case(ui, case, keyboard)
                except Exception:
                    ui.capture('failure')
                    raise
    finally:
        adb('shell','am','force-stop',PACKAGE)  # Discard the in-memory timer draft.
        adb('shell','settings','put','system','font_scale',original_font)
        adb('shell','wm','size',override.group(1) if override else 'reset')
        if original_ime == 'null':
            adb('shell','settings','delete','secure','show_ime_with_hard_keyboard')
        else:
            adb('shell','settings','put','secure','show_ime_with_hard_keyboard',original_ime)


if __name__ == '__main__':
    main()
