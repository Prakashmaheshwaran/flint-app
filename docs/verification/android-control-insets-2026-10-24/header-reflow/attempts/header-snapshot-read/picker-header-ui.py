#!/usr/bin/env python3
"""Final picker header regression on the combined APK; shared lock required."""
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
from native_ui import Ui, adb, bounds, OUT, PACKAGE

root = Path('/Users/prakash/.codex/worktrees/flint-eight-fixes-check/flint')
out = Path('/tmp/flint-fixes-20261024/integration')
OUT.mkdir(exist_ok=True)
private = out / 'private'
private.mkdir(mode=0o700, exist_ok=True)
head = subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip()
gate = json.loads((out/'manifest.json').read_text())
assert gate['head'] == head and gate['checks']['verify-android']['exitCode'] == 0
apk = root/'android-app/app/build/outputs/apk/debug/app-debug.apk'
manifest = {'head':head, 'apkSHA256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'passed':False, 'cases':[]}
original_font = adb('shell','settings','get','system','font_scale').decode().strip()
original_size = adb('shell','wm','size').decode()
original_accessibility = adb('shell','settings','get','secure','accessibility_enabled').decode().strip()
override = re.search(r'Override size: (\d+x\d+)', original_size)
data_dirs = ('files','shared_prefs','databases','no_backup')
adb('shell','am','force-stop',PACKAGE)
existing = [d for d in data_dirs if d in adb('shell','run-as',PACKAGE,'ls').decode().split()]
assert existing
backup = adb('exec-out','run-as',PACKAGE,'tar','-cf','-',*existing)
(private/'android-header-data.tar').write_bytes(backup)

def contents(raw):
    with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        return {e.name:hashlib.sha256(archive.extractfile(e).read()).hexdigest() for e in archive if e.isfile()}

def restore_data():
    adb('shell','am','force-stop',PACKAGE)
    adb('shell','run-as',PACKAGE,'rm','-rf',*data_dirs)
    adb('exec-in','run-as',PACKAGE,'tar','-xf','-',input=backup)
    return contents(backup) == contents(adb('exec-out','run-as',PACKAGE,'tar','-cf','-',*existing))

ui = Ui()
try:
    adb('install','-r',str(apk))
    installed = next(x.removeprefix('package:') for x in adb('shell','pm','path',PACKAGE).decode().splitlines() if x.endswith('/base.apk'))
    assert hashlib.sha256(adb('exec-out','cat',installed)).hexdigest() == manifest['apkSHA256']
    for label, font, size in [('normal-1x','1.0','reset'), ('320dp-2x','2.0','840x1800')]:
        assert restore_data()
        adb('shell','wm','size',size)
        adb('shell','settings','put','system','font_scale',font)
        ui = Ui()
        ui.prefix = 'header-'+label
        ui.launch()
        ui.select_tab('Today')
        ui.find('Start focus')
        ui.check_controls('Start focus')
        ui.tap('Start focus')
        ui.find('Choose what rests')
        ui.tap_navigation('Choose what rests')
        ui.find('Select all', down=False)
        ui.find('Clear')
        counts = [n for n in ui.root.iter('node') if re.fullmatch(r'\d+ APPS?', n.get('text',''))]
        assert len(counts) == 1, 'Expected one visible filtered-app count'
        count = counts[0]
        count_rect = bounds(count)
        action_heights = [bounds(ui.node(x))[3]-bounds(ui.node(x))[1] for x in ['Select all','Clear']]
        # The smaller count label must occupy a single line, not split APP/APPS mid-word.
        assert 0 < count_rect[3]-count_rect[1] <= max(action_heights)*1.2, (count_rect, action_heights)
        scroll = count
        while scroll in ui.parents and scroll.get('scrollable') != 'true':
            scroll = ui.parents[scroll]
        assert scroll.get('scrollable') == 'true'
        viewport = bounds(scroll)
        assert viewport[0] <= count_rect[0] < count_rect[2] <= viewport[2]
        assert viewport[1] <= count_rect[1] < count_rect[3] <= viewport[3]
        ui.check_controls('Select all','Clear','Cancel')
        ui.capture('result-count-readable')
        ui.find('Chrome')
        ui.tap_navigation('Chrome')
        ui.find('1 SELECTED', down=False)
        ui.check_controls('Add · 1','Cancel')
        ui.capture('live-count-and-footer')
        ui.tap('Add · 1')
        ui.wait_for('Block 1 app')
        ui.node('Chrome')
        ui.capture('confirmed-one-app')
        ui.find('Close', down=False)
        ui.tap('Close')
        ui.selected_tab('Today')
        assert not ui.nodes('Close')
        manifest['cases'].append({'name':label,'passed':True,'resultCount':count.get('text'),'resultCountBounds':count_rect,'actionTextHeights':action_heights})
    manifest['passed'] = True
except Exception:
    ui.capture('failure')
    raise
finally:
    manifest['privateFilesRestored'] = restore_data()
    adb('shell','settings','put','system','font_scale',original_font)
    adb('shell','wm','size',override.group(1) if override else 'reset')
    manifest['fontScaleRestored'] = adb('shell','settings','get','system','font_scale').decode().strip() == original_font
    manifest['displaySizeRestored'] = adb('shell','wm','size').decode().strip() == original_size.strip()
    manifest['accessibilityUnchanged'] = adb('shell','settings','get','secure','accessibility_enabled').decode().strip() == original_accessibility
    (out/'picker-header-ui-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    assert all(manifest[x] for x in ['privateFilesRestored','fontScaleRestored','displaySizeRestored','accessibilityUnchanged'])
print(json.dumps(manifest,indent=2),flush=True)
