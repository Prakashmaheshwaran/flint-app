import json, re, subprocess, sys, time
from pathlib import Path
import xml.etree.ElementTree as ET
BASE = Path('/tmp/flint-fixes-20261024/F4')
ADB = '/opt/homebrew/share/android-commandlinetools/platform-tools/adb'
phase = sys.argv[1]
def adb(*args):
    return subprocess.check_output([ADB, '-s', 'emulator-5554', *args])
def dump(name):
    time.sleep(1)
    for attempt in range(5):
        adb('shell', 'rm', '-f', '/sdcard/flint-f4.xml')
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/flint-f4.xml').decode()
        if 'dumped to' in result: break
        time.sleep(2)
    else: raise RuntimeError('No fresh hierarchy: '+name)
    raw = adb('shell', 'cat', '/sdcard/flint-f4.xml')
    (BASE/'evidence'/f'{name}.xml').write_bytes(raw)
    (BASE/'evidence'/f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    return ET.fromstring(raw)
def find(root, text):
    return [n for n in root.iter('node') if text in (n.get('text'),n.get('content-desc'))]
def tap(root, text):
    nodes = find(root,text)
    if not nodes: raise RuntimeError('No control: '+text)
    n=nodes[-1]
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def swipe(width,height):
    adb('shell','input','swipe',str(int(width*.93)),str(int(height*.78)),str(int(width*.93)),str(int(height*.38)),'650')
def bounds(node):
    return tuple(map(int,re.findall(r'\d+',node.get('bounds'))))
def checked_ancestor(root,node):
    parents={child:parent for parent in root.iter() for child in parent}
    while node in parents and node.get('checkable') != 'true' and node.get('selected') != 'true': node=parents[node]
    return node
def clickable_ancestor(root,node):
    parents={child:parent for parent in root.iter() for child in parent}
    while node in parents and node.get('clickable') != 'true': node=parents[node]
    return node
def rule_case(prefix,width,height,narrow):
    adb('shell','am','force-stop','com.flint.peakfocus')
    adb('shell','am','start','-W','-n','com.flint.peakfocus/.MainActivity')
    time.sleep(2)
    root=dump(prefix+'-launch')
    tap(root,'Rules')
    root=dump(prefix+'-rules')
    tap(root,'New block rule')
    root=dump(prefix+'-editor')
    assert find(root,'Rule name'), 'New draft was not opened'
    description='No breaks, no early stop — one Emergency Pass per week.'
    try:
        for attempt in range(18):
            if find(root,'Hardcore') and find(root,description) and find(root,'HARDCORE'): break
            swipe(width,height)
            root=dump(prefix+f'-seek-{attempt}')
        assert find(root,'Hardcore') and find(root,description), 'Hardcore copy unreachable'
        # Center the observed row before capture so the entire description is visible.
        for attempt in range(6):
            title=bounds(find(root,'Hardcore')[0])
            badge=bounds(find(root,'HARDCORE')[0])
            desc=bounds(find(root,description)[0])
            if title[1] >= height*.15 and max(desc[3],badge[3]) <= height*.85: break
            if title[1] < height*.15:
                adb('shell','input','swipe',str(int(width*.93)),str(int(height*.32)),str(int(width*.93)),str(int(height*.49)),'650')
            else:
                adb('shell','input','swipe',str(int(width*.93)),str(int(height*.65)),str(int(width*.93)),str(int(height*.48)),'650')
            root=dump(prefix+f'-position-{attempt}')
        tap(root,'Hardcore')
        root=dump(prefix+'-hardcore-selected')
        row=checked_ancestor(root,find(root,'Hardcore')[0])
        assert row.get('checked')=='true' or row.get('selected')=='true', 'Hardcore selection not reflected'
        desc=bounds(find(root,description)[0])
        badge=bounds(find(root,'HARDCORE')[0])
        assert desc[2] > desc[0] and desc[3] > desc[1]
        if narrow: assert badge[1] >= desc[3], 'Badge still competes with narrow description'
        print(json.dumps({'case':prefix,'selected':True,'description_bounds':desc,'badge_bounds':badge}),flush=True)
    finally:
        root=dump(prefix+'-cleanup-start')
        for attempt in range(12):
            if find(root,'Cancel'): break
            swipe(width,height)
            root=dump(prefix+f'-cancel-seek-{attempt}')
        method='system_back_inherited_F1_footer_inset'
        if find(root,'Cancel'):
            cancel=clickable_ancestor(root,find(root,'Cancel')[-1])
            tab_tops=[bounds(clickable_ancestor(root,find(root,name)[-1]))[1] for name in ['Today','Rules','Insights','You'] if find(root,name)]
            if cancel.get('enabled')=='true' and bounds(cancel)[3] <= min(tab_tops,default=height):
                tap(root,'Cancel')
                method='footer_cancel'
            else:
                adb('shell','input','keyevent','4')
        else:
            adb('shell','input','keyevent','4')
        root=dump(prefix+'-cancelled')
        assert not find(root,'Save rule') and not find(root,'Rule name'), 'Cancel did not leave the draft'
        markers=['Quick block list','BLOCK RULES','Nothing blocked yet','START FROM A TEMPLATE']
        for attempt in range(4):
            if any(find(root,marker) for marker in markers): break
            adb('shell','input','swipe',str(int(width*.93)),str(int(height*.38)),str(int(width*.93)),str(int(height*.78)),'650')
            root=dump(prefix+f'-cancel-destination-{attempt}')
        assert any(find(root,marker) for marker in markers), 'Unique Rules list marker missing after cancellation'
        print(json.dumps({'case':prefix,'draft_cancelled':True,'method':method}),flush=True)
size_state=adb('shell','wm','size').decode()
density_state=adb('shell','wm','density').decode()
original_size = re.search(r'Override size: (\d+x\d+)',size_state)
current_size=re.findall(r'(\d+)x(\d+)',size_state)[-1]
density=int(re.findall(r'(\d+)',density_state)[-1])
(BASE/'logs'/'android-display-state.json').write_text(json.dumps({'size':size_state,'density':density_state}))
try:
    adb('shell','input','keyevent','224')
    for scale,narrow in [('1.0',False),('1.5',False),('2.0',False),('2.0',True)]:
        width,height=map(int,current_size)
        if narrow:
            narrow_width=round(320*density/160)
            height=round(height*narrow_width/width)
            width=narrow_width
            adb('shell','wm','size',f'{width}x{height}')
        adb('shell','settings','put','system','font_scale',scale)
        adb('shell','am','force-stop','com.flint.peakfocus')
        adb('shell','am','start','-W','-n','com.flint.peakfocus/.MainActivity')
        time.sleep(2)
        prefix=f'android-{phase}-{scale}'+('-narrow' if narrow else '')
        root=dump(prefix+'-launch')
        tap(root,'Today')
        root=dump(prefix+'-today')
        assert any(n.get('text','').casefold() == 'your next session' for n in root.iter('node')), 'Today setup not established'
        for attempt in range(3):
            if find(root,'Start focus'): break
            swipe(width,height)
            root=dump(prefix+f'-today-scroll-{attempt}')
        tap(root,'Start focus')
        root=dump(prefix+'-start-top')
        for attempt in range(5):
            if find(root,'Hardcore'): break
            swipe(width,height)
            root=dump(prefix+f'-levels-scroll-{attempt}')
        tap(root,'Hardcore')
        root=dump(prefix+'-levels-selected')
        parents={child:parent for parent in root.iter() for child in parent}
        node=find(root,'Hardcore')[-1]
        while node in parents and node.get('checkable') != 'true': node=parents[node]
        assert node.get('checked') == 'true' or node.get('selected') == 'true', ET.tostring(node).decode()
        print(json.dumps({'case':prefix,'hardcore_control':node.attrib}),flush=True)
        for _ in range(3): swipe(width,height)
        root=dump(prefix+'-start-bottom')
        assert any(n.get('text','').startswith('Begin ') for n in root.iter('node')), 'Begin unreachable'
        adb('shell','input','keyevent','4')
    for scale,narrow in [('1.0',False),('1.5',True),('2.0',True)]:
        width,height=map(int,current_size)
        if narrow:
            narrow_width=round(320*density/160)
            height=round(height*narrow_width/width)
            width=narrow_width
            adb('shell','wm','size',f'{width}x{height}')
        else:
            adb('shell','wm','size',original_size.group(1) if original_size else 'reset')
        adb('shell','settings','put','system','font_scale',scale)
        rule_case(f'android-{phase}-rule-{scale}'+('-narrow' if narrow else ''),width,height,narrow)
finally:
    adb('shell','wm','size',original_size.group(1) if original_size else 'reset')
    adb('shell','settings','put','system','font_scale','1.0')
    adb('shell','am','force-stop','com.flint.peakfocus')
    adb('shell','am','start','-W','-n','com.flint.peakfocus/.MainActivity')
    (BASE/'logs'/'android-cleanup.txt').write_bytes(adb('shell','settings','get','system','font_scale')+adb('shell','wm','size'))
