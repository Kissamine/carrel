import json
S=1024; W="#FFFFFF"
G=json.load(open('glyphs.json'))["SavoyeLetPlain"]
H=640; sc=H/G['h']; w=G['w']*sc
tx=(S-w)/2 - G['x']*sc; ty=(S-H)/2 - G['y']*sc
d=G['d']
def icon(uid="a", grain=0.11, stroke=48):
    defs=f'''<defs>
  <linearGradient id="{uid}sea" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#36CBE4"/><stop offset="0.5" stop-color="#0BB5D6"/><stop offset="1" stop-color="#0E7FC4"/></linearGradient>
  <radialGradient id="{uid}glow" cx="0.3" cy="0.22" r="0.8"><stop offset="0" stop-color="#fff" stop-opacity="0.22"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
  <filter id="{uid}sand" x="0" y="0" width="1" height="1" color-interpolation-filters="sRGB">
    <feTurbulence type="fractalNoise" baseFrequency="4.5" numOctaves="2" seed="11" result="n"/>
    <feColorMatrix in="n" type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0.4 0.4 0.4 0 -0.35" result="w"/>
    <feTurbulence type="fractalNoise" baseFrequency="0.09" numOctaves="3" seed="4" result="rip"/>
    <feColorMatrix in="rip" type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0.9 0.9 0.9 0 -0.5" result="ripw"/>
    <feMerge><feMergeNode in="ripw"/><feMergeNode in="w"/></feMerge></filter>
  <clipPath id="{uid}rr"><rect width="{S}" height="{S}" rx="230"/></clipPath></defs>'''
    body=f'<g clip-path="url(#{uid}rr)"><rect width="{S}" height="{S}" fill="url(#{uid}sea)"/><rect width="{S}" height="{S}" fill="url(#{uid}glow)"/><rect width="{S}" height="{S}" filter="url(#{uid}sand)" opacity="{grain}"/></g>'
    body+=f'<g transform="translate({tx:.1f} {ty:.1f}) scale({sc:.4f})"><path d="{d}" fill="{W}" stroke="{W}" stroke-width="{stroke/sc:.2f}" stroke-linejoin="round" stroke-linecap="round"/></g>'
    return defs+body
open("carrel_final.svg","w").write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {S} {S}">{icon()}</svg>')
cell=560; pad=40; SW=cell+2*pad+ 420
sheet=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {SW} {SW}" width="{SW}" height="{SW}"><rect width="100%" height="100%" fill="#F3F4F6"/>'
sheet+=f'<g transform="translate({pad} {pad}) scale({cell/S})">{icon(uid="m")}</g>'
sx=pad+cell+40
for sz in (256,128,64,32,16):
    sheet+=f'<g transform="translate({sx} {pad+(256-sz)}) scale({sz/S})">{icon(uid="s")}</g>'; sx+= (sz+20) if sz>64 else 0
    if sz==64: sx=pad+cell+40
    if sz==32: sx=pad+cell+40+84
sheet+=f'<rect x="{pad}" y="{pad+cell+40}" width="{SW-2*pad}" height="{SW-cell-3*pad-40}" rx="16" fill="#1C1C1E"/>'
sheet+=f'<g transform="translate({pad+30} {pad+cell+70}) scale({128/S})">{icon(uid="d")}</g><g transform="translate({pad+200} {pad+cell+118}) scale({32/S})">{icon(uid="e")}</g>'
sheet+='</svg>'
open("sheet_final.svg","w").write(sheet)
