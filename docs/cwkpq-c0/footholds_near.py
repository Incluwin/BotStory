import sys, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1]).getroot()
x0, x1, y0, y1 = map(int, sys.argv[2:6])
def kid(e, n):
    for c in e:
        if c.get('name') == n: return c
fh = kid(t, 'foothold')
for layer in fh:
    for group in layer:
        for f in group:
            v = {c.get('name'): int(c.get('value')) for c in f if c.get('value') is not None}
            if max(v['x1'], v['x2']) >= x0 and min(v['x1'], v['x2']) <= x1 and min(v['y1'], v['y2']) <= y1 and max(v['y1'], v['y2']) >= y0:
                print('layer', layer.get('name'), 'group', group.get('name'), 'id', f.get('name'), v)
