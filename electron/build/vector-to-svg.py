"""Android のベクター図 (ic_launcher_*.xml) を SVG に直す。
グラデーション・切り抜き・evenOdd に対応する (このアイコンで使っている範囲)。"""
import re, sys, xml.etree.ElementTree as ET

A = 'http://schemas.android.com/apk/res/android'
def at(el, name): return el.get('{%s}%s' % (A, name))

def color(v):
    if not v: return None, None
    v = v.strip()
    if len(v) == 9:      # #AARRGGBB
        a = int(v[1:3], 16) / 255.0
        return '#' + v[3:], round(a, 4)
    return v, None

def grad(el, idx, out):
    g = el.find('.//{http://schemas.android.com/apk/res/android}gradient')
    if g is None:
        g = el.find('.//gradient')
    if g is None: return None
    kind = at(g, 'type') or 'linear'
    gid = 'g%d' % idx
    stops = ''
    for item in g:
        c, a = color(at(item, 'color'))
        off = at(item, 'offset') or '0'
        stops += '<stop offset="%s" stop-color="%s" stop-opacity="%s"/>' % (off, c, a if a is not None else 1)
    if kind == 'radial':
        out.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%s" cy="%s" r="%s">%s</radialGradient>'
                   % (gid, at(g, 'centerX'), at(g, 'centerY'), at(g, 'gradientRadius'), stops))
    else:
        out.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>'
                   % (gid, at(g, 'startX'), at(g, 'startY'), at(g, 'endX'), at(g, 'endY'), stops))
    return 'url(#%s)' % gid

def convert(path, seed=0):
    tree = ET.parse(path)
    root = tree.getroot()
    defs, body = [], []
    n = [seed]

    def paint(el, kind):
        """fill / stroke の色を決める (単色か、入れ子のグラデーションか)"""
        holder = None
        for child in el:
            if child.tag.endswith('attr') and child.get('name', '').endswith(kind + 'Color'):
                holder = child
        if holder is not None:
            n[0] += 1
            return grad(holder, n[0], defs), None
        c, a = color(at(el, kind + 'Color'))
        return c, a

    def emit(el, clip=None):
        d = at(el, 'pathData')
        if d is None: return
        f, fa = paint(el, 'fill')
        s, sa = paint(el, 'stroke')
        w = at(el, 'strokeWidth')
        bits = ['d="%s"' % d]
        bits.append('fill="%s"' % (f or 'none'))
        if fa is not None: bits.append('fill-opacity="%s"' % fa)
        if s:
            bits.append('stroke="%s"' % s)
            if sa is not None: bits.append('stroke-opacity="%s"' % sa)
            bits.append('stroke-width="%s"' % (w or 1))
            bits.append('stroke-linejoin="%s"' % (at(el, 'strokeLineJoin') or 'miter'))
            bits.append('stroke-linecap="round"')
        if at(el, 'fillType') == 'evenOdd': bits.append('fill-rule="evenodd"')
        if clip: bits.append('clip-path="url(#%s)"' % clip)
        body.append('<path %s/>' % ' '.join(bits))

    for el in root:
        tag = el.tag.split('}')[-1]
        if tag == 'path':
            emit(el)
        elif tag == 'group':
            cp = el.find('{%s}clip-path' % A)
            cid = None
            if cp is not None:
                n[0] += 1
                cid = 'c%d' % n[0]
                defs.append('<clipPath id="%s"><path d="%s"/></clipPath>' % (cid, at(cp, 'pathData')))
            for sub in el:
                if sub.tag.split('}')[-1] == 'path': emit(sub, cid)
    return ''.join(defs), ''.join(body)

bg_defs, bg_body = convert(sys.argv[1], 0)
fg_defs, fg_body = convert(sys.argv[2], 100)
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<defs>{bg_defs}{fg_defs}
<clipPath id="round"><rect x="92" y="92" width="840" height="840" rx="188" ry="188"/></clipPath>
</defs>
<g clip-path="url(#round)">
  <g transform="translate(92,92) scale(7.7778)">{bg_body}</g>
  <g transform="translate(92,92) scale(7.7778)">{fg_body}</g>
</g>
</svg>'''
open(sys.argv[3], 'w').write(svg)
print('書き出しました', sys.argv[3])
