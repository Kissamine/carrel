# App Store の「ヘッダ」(21:9) と「検索結果」(3:2) のクリエイティブアセットを組み立てる
import os, subprocess
from PIL import Image
S = '/private/tmp/claude-501/-Users-keitahomma-Downloads-ankieditor/e1165281-609f-460d-9559-84815fb1d8ad/scratchpad'
OUT = '/Users/keitahomma/Downloads/ankieditor/ios/AppStore/Creative'
os.makedirs(OUT, exist_ok=True)
ICON = S + '/icon.png'

def phone(shot, w, rot, x, y, z=1):
    # 端末風の枠に入れたスクリーンショット (スクショは 1206x2622 相当の縦長)
    h = int(w * 2622 / 1206); b = int(w * 0.03); r = int(w * 0.13)
    return f'''<div style="position:absolute;left:{x}px;top:{y}px;width:{w+2*b}px;height:{h+2*b}px;border-radius:{r+b}px;
      background:#1b1d22;box-shadow:0 60px 120px rgba(0,0,0,.6),0 0 0 2px rgba(255,255,255,.08) inset;transform:rotate({rot}deg);z-index:{z}">
      <div style="position:absolute;left:{b}px;top:{b}px;width:{w}px;height:{h}px;border-radius:{r}px;overflow:hidden;background:#000">
        <img src="{S}/shots/{shot}.png" style="display:block;width:100%;height:100%"></div></div>'''

def page(W, H, body, hue='#0E7FC4'):
    return f'''<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;width:{W}px;height:{H}px;overflow:hidden;font-family:-apple-system,"Hiragino Sans","Helvetica Neue",sans-serif;color:#fff}}
body{{background:radial-gradient(90% 90% at 18% 30%, {hue}73 0%, transparent 60%),
  radial-gradient(70% 80% at 85% 100%, #36CBE455 0%, transparent 55%),
  linear-gradient(180deg,#121417 0%,#0B0C0F 100%)}}
.icon{{border-radius:22.5%;box-shadow:0 40px 90px rgba(54,203,228,.35)}}
.word{{font-weight:800;letter-spacing:-.01em;line-height:1}}
.tag{{font-weight:600;color:rgba(255,255,255,.86);line-height:1.35}}
.sub{{font-weight:500;color:rgba(255,255,255,.6);line-height:1.5}}
</style></head><body>{body}</body></html>'''

def render(name, W, H, body):
    hp = f'{S}/asset_{name}.html'; open(hp, 'w').write(page(W, H, body))
    out = f'{OUT}/{name}.png'
    r = subprocess.run(['/usr/bin/swift', f'{S}/html2png.swift', hp, out, str(W), str(H)], capture_output=True, text=True)
    if r.returncode != 0: print(r.stdout, r.stderr); return
    im = Image.open(out).convert('RGB')
    if im.size != (W, H): im = im.resize((W, H), Image.LANCZOS)
    im.save(out, optimize=True); print(name, im.size, os.path.getsize(out) // 1024, 'KB')

# ---- ヘッダ 21:9 (3840 x 1646) ----
W, H = 3840, 1646
body = f'''
<img class="icon" src="{ICON}" style="position:absolute;left:260px;top:{(H-520)//2}px;width:520px;height:520px">
<div style="position:absolute;left:900px;top:{(H-640)//2}px;width:1500px">
  <div class="word" style="font-size:300px">Carrel</div>
  <div class="tag" style="font-size:96px;margin-top:40px">見て・学んで・作れる暗記カード</div>
  <div class="sub" style="font-size:56px;margin-top:28px">英単語、資格試験、歴史。覚えたいことを、ひとつの場所に。</div>
</div>
{phone('s_list', 560, -8, 2500, 260, 1)}
{phone('s_card', 560, 6, 3080, 120, 2)}
'''
render('header_21x9', W, H, body)

# ---- 検索結果 3:2 (3840 x 2560) ----
W, H = 3840, 2560
body = f'''
<img class="icon" src="{ICON}" style="position:absolute;left:300px;top:380px;width:460px;height:460px">
<div style="position:absolute;left:860px;top:360px;width:1300px">
  <div class="word" style="font-size:280px">Carrel</div>
  <div class="tag" style="font-size:80px;margin-top:36px;white-space:nowrap">見て・学んで・作れる暗記カード</div>
</div>
<div class="sub" style="position:absolute;left:300px;top:1040px;width:1700px;font-size:60px">
  タグとデッキで絞り込み。間隔反復で学び、カードもその場で作れます。<br>.anki2 形式のファイルと互換。
</div>
{phone('s_list', 780, -7, 2180, 420, 1)}
{phone('s_study', 780, 5, 2920, 260, 2)}
'''
render('search_3x2', W, H, body)
