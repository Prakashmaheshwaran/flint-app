"""UIAutomator/physical-tap helper adapted from the read-only audit harness.

Run only while holding the Flint shared build/device lock.
"""
import json
import os
import shutil
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = os.environ.get('ADB', shutil.which('adb') or 'adb')
SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
PACKAGE = 'com.flint.peakfocus'
OUT = Path(os.environ.get('FLINT_UI_EVIDENCE', '/tmp/flint-control-insets-proof'))

def adb(*args, input=None):
    return subprocess.check_output([ADB, '-s', SERIAL, *args], input=input, timeout=45)

def bounds(node):
    return tuple(map(int, re.findall(r'-?\d+', node.get('bounds'))))

class Ui:
    def __init__(self):
        self.root = None
        self.prefix = 'normal'
        OUT.mkdir(parents=True, exist_ok=True)

    def dump(self):
        # Every input/navigation action invalidates this snapshot. Reuse only
        # within the same observed state, avoiding repeated UiAutomation startups.
        if self.root is not None:
            return self.root
        for attempt in range(3):
            adb('shell', 'rm', '-f', '/sdcard/flint-f1-window.xml')
            result = adb('shell', 'uiautomator', 'dump', '/sdcard/flint-f1-window.xml')
            if b'dumped to' in result:
                break
        else:
            raise AssertionError('No fresh UI hierarchy')
        self.raw = adb('shell', 'cat', '/sdcard/flint-f1-window.xml')
        self.root = ET.fromstring(self.raw)
        self.parents = {child: parent for parent in self.root.iter() for child in parent}
        return self.root

    def nodes(self, label):
        return [n for n in self.root.iter('node')
                if label in (n.get('text'), n.get('content-desc'))]

    def node(self, label):
        matches = self.nodes(label)
        assert matches, f'Missing control: {label}'
        return matches[-1]

    def clickable(self, n):
        while n.get('clickable') != 'true' and n in self.parents:
            n = self.parents[n]
        assert n.get('clickable') == 'true', 'No clickable parent'
        return n

    def tap(self, label):
        self.root = None  # Insets/restored navigation can change after a prior dump.
        self.dump()
        n = self.clickable(self.node(label))
        assert n.get('enabled') == 'true', f'Disabled: {label}'
        x1,y1,x2,y2 = bounds(n)
        assert x2 > x1 and y2 > y1
        x,y = (x1+x2)//2,(y1+y2)//2
        print(json.dumps({'tap':label,'bounds':bounds(n),'center':[x,y]}), flush=True)
        adb('shell', 'input', 'tap', str(x), str(y))
        self.root = None
        time.sleep(.5)
        self.dump()

    def capture(self, name):
        self.dump()
        path = OUT / (self.prefix+'-'+name)
        path.with_suffix('.xml').write_bytes(self.raw)
        path.with_suffix('.png').write_bytes(adb('exec-out','screencap','-p'))
        print('CAPTURE', path.name, flush=True)

    def key(self, key='BACK'):
        adb('shell', 'input', 'keyevent', key)
        self.root = None
        time.sleep(.5)
        self.dump()

    def text(self, value):
        adb('shell', 'input', 'text', value.replace(' ', '%s'))
        self.root = None
        time.sleep(.5)
        self.dump()

    def scroll(self, down=True):
        self.dump()
        scrolls = [n for n in self.root.iter('node')
                   if n.get('scrollable') == 'true' and n.get('package') == PACKAGE
                   and n.get('class') != 'android.widget.EditText']
        if not scrolls:
            return False  # Content already fits; Compose exposes no scroll action.
        n = max(scrolls, key=lambda n: bounds(n)[3]-bounds(n)[1])
        x1,y1,x2,y2 = bounds(n)
        windows = adb('shell','dumpsys','window','displays').decode()
        ime_tops = re.findall(r'type=ime.*?frame=\[\d+,(\d+)\]\[\d+,\d+\].*?visible=true', windows)
        y2 = min([y2] + [int(y) for y in ime_tops])
        if y2-y1 < 80 or x2 <= x1:
            return False  # Never turn a clipped/zero-length swipe into a tap.
        # Stay in the scroll container's gutter, away from child action centers.
        x = x1+min(32,(x2-x1)//4)
        high,low = int(y1+(y2-y1)*.5),int(y1+(y2-y1)*.9)
        start,end = (low,high) if down else (high,low)
        print(json.dumps({'scroll_node':bounds(n), 'gesture_viewport':[x1,y1,x2,y2],
                          'swipe':[x,start,x,end]}), flush=True)
        adb('shell','input','swipe',str(x),str(start),str(x),str(end),'650')
        self.root = None
        time.sleep(.5)
        self.dump()
        return True

    def find(self, label, down=True, attempts=15):
        self.dump()
        for _ in range(attempts):
            if self.nodes(label):
                return self.node(label)
            previous = [(n.get('text'), n.get('content-desc'), n.get('bounds'))
                        for n in self.root.iter('node') if n.get('package') == PACKAGE]
            if not self.scroll(down):
                break
            current = [(n.get('text'), n.get('content-desc'), n.get('bounds'))
                       for n in self.root.iter('node') if n.get('package') == PACKAGE]
            if previous == current:
                break  # Report an unreachable target instead of repeating unchanged swipes.
        raise AssertionError('Could not scroll to '+label)

    def bottom(self):
        for _ in range(12):
            self.dump()
            previous = [(n.get('text'), n.get('bounds')) for n in self.root.iter('node')]
            self.scroll()
            current = [(n.get('text'), n.get('bounds')) for n in self.root.iter('node')]
            if previous == current:
                return
        raise AssertionError('No stable end of scroll')

    def check_controls(self, *labels):
        self.dump()
        # You is the tab that intercepted the original Add tap. It is unselected
        # in both Today and Rules, unlike selected tabs that may omit click actions.
        tabs = [self.clickable(self.node('You'))] if self.nodes('You') else []
        nav_top = min((bounds(n)[1] for n in tabs), default=10000)
        ime = adb('shell','dumpsys','input_method').decode()
        keyboard = 'mInputShown=true' in ime
        windows = adb('shell','dumpsys','window','displays').decode()
        ime_bounds = re.findall(r'type=ime.*?frame=\[\d+,(\d+)\]\[\d+,\d+\].*?visible=true', windows)
        assert not keyboard or ime_bounds, 'Missing visible IME frame'
        ceiling = min([nav_top]+[int(y) for y in ime_bounds]) if keyboard else nav_top
        for label in labels:
            n = self.clickable(self.node(label))
            x1,y1,x2,y2 = bounds(n)
            assert y1 > 0 and y2 <= ceiling, f'{label} overlaps tab/IME: {bounds(n)} ceiling={ceiling}'
            print(json.dumps({'visible_control':label,'bounds':bounds(n),'ceiling':ceiling,'keyboard':keyboard}), flush=True)

    def selected_tab(self, label):
        self.dump()
        n = self.node(label)
        while n.get('selected') != 'true' and n in self.parents:
            n = self.parents[n]
        assert n.get('selected') == 'true', f'{label} tab lost'

    def select_tab(self, label):
        """Establish a starting tab; flow assertions still use selected_tab/content."""
        self.dump()
        n = self.node(label)
        while n.get('selected') != 'true' and n in self.parents:
            n = self.parents[n]
        if n.get('selected') != 'true':
            self.tap(label)

    def launch(self):
        adb('shell','am','force-stop',PACKAGE)
        adb('shell','am','start','-W','-n',PACKAGE+'/.MainActivity')
        self.root = None
        time.sleep(2)  # Let the first Compose frame settle after activity launch.
        self.dump()

    def summary(self):
        self.dump()
        for n in self.root.iter('node'):
            if n.get('text') or n.get('content-desc') or n.get('class','').endswith('EditText'):
                print({k:n.get(k) for k in ('text','content-desc','class','bounds','selected','checked')})

if __name__ == '__main__':
    ui = Ui()
    ui.summary()
