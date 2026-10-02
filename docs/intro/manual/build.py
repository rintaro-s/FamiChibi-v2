"""使い方マニュアル（5ページ）を生成する。python3 build.py"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PAGES = [
    ("index.html", "マニュアル", "📖"),
    ("server.html", "サーバーの建て方", "🖥"),
    ("app.html", "アプリをつなぐ", "📱"),
    ("avatar.html", "アバター(VRM)", "🧸"),
    ("daily.html", "毎日の使い方", "🏠"),
]


def vid(name, poster=""):
    return f'<div class="phone"><video src="media/{name}.mp4" muted loop playsinline preload="metadata"></video></div>'


def img(src, alt):
    return f'<div class="phone"><img src="{src}" alt="{alt}" loading="lazy"></div>'


def page(fname, title, lead, chips, body):
    idx = [p[0] for p in PAGES].index(fname)
    nav = "".join(f'<a href="{f}" class="{"on" if f == fname else ""}">{e} {t}</a>' for f, t, e in PAGES)
    prev = PAGES[idx - 1] if idx > 0 else None
    nxt = PAGES[idx + 1] if idx + 1 < len(PAGES) else None
    pager = '<div class="pager">'
    pager += f'<a href="{prev[0]}"><small>← 前へ</small>{prev[2]} {prev[1]}</a>' if prev else '<a href="../index.html"><small>← 戻る</small>🏠 紹介ページ</a>'
    pager += f'<a class="next" href="{nxt[0]}"><small>次へ →</small>{nxt[2]} {nxt[1]}</a>' if nxt else '<a class="next" href="../index.html"><small>トップへ →</small>🏠 紹介ページ</a>'
    pager += "</div>"
    chips_html = "".join(f"<span>{c}</span>" for c in chips)
    return f"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} | FamiChibi 使い方マニュアル</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=M+PLUS+Rounded+1c:wght@400;700;800&display=swap" rel="stylesheet">
<link rel="stylesheet" href="manual.css"></head>
<body>
<div class="top"><div class="wrap"><a class="logo" href="../index.html">🏠 FamiChibi</a>{nav}</div></div>
<div class="wrap">
<div class="head"><div class="crumb">使い方マニュアル {idx + 1} / {len(PAGES)}</div><h1>{title}</h1><p>{lead}</p><div class="point-bar">{chips_html}</div></div>
{body}
{pager}
</div>
<footer>FamiChibi — Team Daijobu（鳥羽商船高等専門学校）／ 画面はすべて実機（Android 15 / 16）で撮影</footer>
<script>
// show steps when scrolled into view; play the clip only while it is visible
const io = new IntersectionObserver((es) => es.forEach((e) => {{
  if (e.isIntersecting) e.target.classList.add('in');
  e.target.querySelectorAll('video').forEach((v) => e.isIntersecting ? v.play().catch(() => {{}}) : v.pause());
}}), {{ threshold: .25 }});
document.querySelectorAll('.step, .video-box').forEach((s) => io.observe(s));
</script>
</body></html>
"""


def step(n, title, html, media="", wide=False):
    num = f'<div class="num">{n}</div>' if n else ""
    if wide:
        return f'<section class="step wide"><div>{num}<h2>{title}</h2>{html}</div>{media}</section>'
    return f'<section class="step"><div>{num}<h2>{title}</h2>{html}</div><div>{media}</div></section>'


pages = {}

# ------------------------------------------------------------------------------------------- index
pages["index.html"] = page(
    "index.html", "使い方マニュアル",
    "FamiChibiは「家族サーバー」と「アプリ」の2つでできています。サーバーを1台立てて、家族のスマホからつなぐだけ。",
    ["⏱ はじめるまで 約5分", "📱 Android 10以上", "🖥 PC・ラズパイ・VPS"],
    """
<div class="video-box"><video src="media/famichibi-demo.mp4" controls muted playsinline preload="metadata" poster="media/demo-poster.jpg"></video></div>
<div class="flow"><span>🖥 サーバーを立てる</span><i>→</i><span>📱 アプリでつなぐ</span><i>→</i><span>🧸 アバターを選ぶ</span><i>→</i><span>🏠 家族のリビングへ</span></div>
<div class="cards">
  <a class="card" href="server.html"><span class="ic">🖥</span><h3>1. サーバーの建て方</h3><p>PC・ラズパイ・VPSでコマンド1つ。外から使うときのポート開放も。</p><span class="go">読む →</span></a>
  <a class="card" href="app.html"><span class="ic">📱</span><h3>2. アプリをつなぐ</h3><p>サーバーは自動で見つかります。招待コードかNFCカードで家族に参加。</p><span class="go">読む →</span></a>
  <a class="card" href="avatar.html"><span class="ic">🧸</span><h3>3. アバター(VRM)</h3><p>3Dで選ぶ・自分のVRMを使う・つかんで投げる。</p><span class="go">読む →</span></a>
  <a class="card" href="daily.html"><span class="ic">🏠</span><h3>4. 毎日の使い方</h3><p>ちびっこが届ける連絡、けはい、見守り、AI、サイレント。</p><span class="go">読む →</span></a>
</div>
<section class="step wide"><div><h2>FamiChibiの3つのポイント</h2>
<ol>
<li><b>連絡は、送った本人の“ちびっこ”が歩いて届ける</b> — 吹き出し・表情・声で、誰からの連絡かひと目でわかる。急ぎは見逃させない。</li>
<li><b>家族の「けはい」が見えるリビング</b> — 帰宅・外出・睡眠を、本人の操作なしで。カメラも位置情報も使わない見守り。</li>
<li><b>AIは「なぜ？」を話す</b> — 家電操作・予定確認・代わりに聞く。判断根拠をいつでも表示。</li>
</ol></div></section>
""")

# ------------------------------------------------------------------------------------------- server
pages["server.html"] = page(
    "server.html", "サーバーの建て方",
    "家族のメッセージや予定は、自分たちで動かす「家族サーバー」に保存します。Python 3.11以上が入ったPC・Raspberry Pi・VPSならどれでも動きます。",
    ["🐍 Python 3.11+", "⌨ コマンド1つ", "🔒 データは家族の手元に"],
    step(1, "ダウンロードして起動", """
<p>サーバー用のフォルダで <code>run.sh</code> を実行するだけです。初回は必要なライブラリを自動でインストールします。</p>
<pre><code><span class="c"># 作品フォルダの server/ に移動して</span>
cd famichibi/server
./run.sh --port 8010 --name "わが家のサーバー"</code></pre>
<p>Windowsの場合は <code>python -m venv .venv</code> → <code>.venv\\Scripts\\pip install -r requirements.txt</code> → <code>.venv\\Scripts\\python -m app.main --port 8010</code>。</p>
<div class="note">💡 同じWi-Fiのスマホは、アプリを開くだけでこのサーバーを<b>自動で見つけます</b>（UDP 47810番でお知らせ）。</div>
""", '<div class="media-wide"><img src="media/server-terminal.png" alt="サーバーを起動したときの画面"></div>', wide=True)
    + step(2, "ずっと動かしておく（ラズパイ・VPS）", """
<p>電源を入れたら自動で起動するよう、systemdに登録します（Linux）。</p>
<pre><code><span class="c"># /etc/systemd/system/famichibi.service</span>
[Service]
WorkingDirectory=/home/pi/famichibi/server
Environment=FAMICHIBI_DATA=/home/pi/famichibi/data
Environment=FAMICHIBI_PUBLIC_URLS=http://（外から見たアドレス）:8010
ExecStart=/home/pi/famichibi/server/.venv/bin/python -m app.main --port 8010
Restart=always
[Install]
WantedBy=multi-user.target</code></pre>
<pre><code>sudo systemctl enable --now famichibi</code></pre>
<p>VPSなら付属の <code>tools/deploy_vps.sh ユーザー@アドレス</code> で、コピー・インストール・自動起動までまとめて行えます。</p>
""", wide=True)
    + step(3, "家の外からも使う（ポート開放）", """
<ul>
<li><b>VPS</b>：ファイアウォールでTCP 8010番を開けます（例：<code>sudo ufw allow 8010/tcp</code>）。</li>
<li><b>家のPC・ラズパイ</b>：ルーターでTCP 8010番をサーバーへ転送します。転送できない回線では、VPSにサーバーを置くのがおすすめです。</li>
<li><code>FAMICHIBI_PUBLIC_URLS</code> に外から見たアドレスを書いておくと、アプリが<b>家のWi-Fi ⇄ 外</b>でつなぎ先を自動で切り替えます。</li>
</ul>
<div class="warn">⚠ 学校や会社のWi-Fiでは、8010番などの番号への通信が止められていることがあります。その場合はモバイル回線か家のWi-Fiでお試しください。</div>
""", wide=True)
    + step(4, "AI・家電はあとから（任意）", """
<ul>
<li><b>AIちびっこ</b>：LLMがなくてもルールエンジンで動きます。雑談も楽しみたい場合は <a href="https://ollama.com/">Ollama</a> を入れて <code>ollama pull qwen3.5:9b</code>。アプリの「家族」タブ → AIちびっこ設定でURLとモデルを指定します。</li>
<li><b>家電（Home Assistant）</b>：同じ設定画面の「MCPサーバー」に ha-mcp のURLを追加します。最初は家電が入った<b>仮想の家</b>で試せます。</li>
</ul>
""", wide=True))

# ------------------------------------------------------------------------------------------- app
pages["app.html"] = page(
    "app.html", "アプリをつなぐ",
    "アプリを開いてから家族のリビングに入るまで、実機で約1分半。むずかしい入力はほとんどありません。",
    ["🔍 サーバー自動発見", "🧸 3Dでアバター選び", "🔑 招待コード / NFC"],
    step(1, "アプリを開くと、サーバーが見つかる", """
<p>「はじめる」を押すと、同じWi-Fiの家族サーバーと、登録済みのサーバー（例：VPS）を自動で探します。見つかったサーバーをタップするだけ。</p>
<p>見つからないときは、URL（例：<code>http://192.168.0.40:8010</code>）を入力して「接続する」。</p>
""", vid("connect"))
    + step(2, "名前とアバターを決める", """
<p>家族のスマホには、このちびっこがあなたの言葉を届けに行きます。名前の下のボタンでアバターを切り替えると、上の3Dプレビューがすぐ変わります。</p>
<p>誕生日（任意）を入れておくと、当日0時に家族のちびっこが集まってお祝いします。</p>
<p>👉 くわしくは <a href="avatar.html">アバター(VRM)</a></p>
""", vid("avatar"))
    + step(3, "家族サーバーに参加する", """
<ul>
<li><b>招待コード</b>：家族に教えてもらった6文字のコードを入力（全角・小文字でもOK）。</li>
<li><b>QRコード / NFCカード</b>：家族の「家族」タブで招待を作り、QRを読む・カードをかざすだけで参加。<b>名前入りのカード</b>なら、おじいちゃん・おばあちゃんは入力ゼロ。</li>
<li>はじめての家族は「新しく作る」、試すだけなら「デモ家族」。</li>
</ul>
""", vid("join"))
    + step(4, "権限を許可して、リビングへ", """
<p>「他のアプリの上に表示」を許可すると、ホーム画面にもちびっこが歩いてきます。通知・マイク・歩数は任意です。</p>
<div class="note">💡 Xiaomi / OPPOなど一部の機種では、設定アプリの「他のアプリの上に表示（ポップアップ表示）」も確認してください。</div>
""", vid("room-first")))

# ------------------------------------------------------------------------------------------- avatar
pages["avatar.html"] = page(
    "avatar.html", "アバター(VRM)",
    "ちびっこには、メタバースやVTuberで広く使われるVRM形式の3Dアバターを使います。普段使っている自分のアバターも、そのまま家族のもとへ。",
    ["🧸 VRM 0.x / 1.0 対応", "😊 5つの感情表現", "🤸 29のモーション"],
    step(1, "選ぶ・変える", """
<p>はじめに選んだアバターは、あとから <b>設定 → プロフィール</b> でいつでも変えられます。変えた瞬間、家族全員のリビングとホーム画面のちびっこも入れ替わります。</p>
<p>同梱アバター：ユウ・ミナ・ハル・ソラ・サクラ・アリシア（AIちびっこ）</p>
""", img("media/onboard_26.jpg", "アバター選択画面"))
    + step(2, "自分のVRMを使う", """
<ol>
<li>アバター一覧の右端「<b>＋ VRMを追加</b>」をタップ</li>
<li>VRoid Studio などで作った <code>.vrm</code> ファイルを選ぶ（80MBまで）</li>
<li>家族サーバーにアップロードされ、家族のスマホにも自動で届きます</li>
</ol>
<div class="note">💡 スマホで軽く動くよう、表示時にテクスチャを自動で縮小します。作者名・ライセンスはクレジットとして表示されます。</div>
""", img("media/onboard_47.jpg", "VRMを追加ボタンのある画面"))
    + step(3, "気持ちに合わせて動く", """
<p>メッセージの内容から気持ちと急ぎ具合を読みとり、表情・動き・エフェクトを自動で選びます。</p>
<ul>
<li>「ありがとう🙏」→ おじぎ　「大好き❤」→ 両手で手を振る　「迎えに来て」→ 窓をノック</li>
<li>話している間は口が動き、目線はスマホを見ている人の方へ</li>
<li>家族ごとに声の高さが違う読み上げ（設定で変更可）</li>
</ul>
""", vid("arrive"))
    + step(4, "つかんで投げる", """
<p>ホーム画面のちびっこは指でつかんで動かせます。勢いよく投げると跳ねて着地し、画面の外まで投げると…すねて、あとで端からのぞきにきます。</p>
<p>表示の大きさ・常駐するかどうかは <b>設定 → ちびっこ</b> で変えられます。</p>
""", '<div class="media-wide"><img src="../img/home-arrive.jpg" alt="ホーム画面のちびっこ"></div>'))

# ------------------------------------------------------------------------------------------- daily
pages["daily.html"] = page(
    "daily.html", "毎日の使い方",
    "ふだんはアプリを開かなくても大丈夫。家族の連絡と「けはい」が、ちびっこに乗ってやってきます。",
    ["💬 ちびっこが届ける", "🏠 けはい・見守り", "🤖 AIは「なぜ？」を話す"],
    step(1, "連絡は、ちびっこが届ける", """
<p>家族がメッセージを送ると、<b>その人のちびっこ</b>が画面の端から歩いてきて、吹き出しで伝えます。吹き出しのボタン（「今行く！」など）をタップすれば、そのまま返事。相手のスマホには<b>あなたのちびっこ</b>が届けに行きます。</p>
<ul><li><b>急ぎ</b>（迎えに来て・至急 など）：ロック画面にも表示、1分未読で再ノック、2.5分誰も見なければAIが家族全員に呼びかけ</li>
<li>見たら送った人に「見たよ！」</li></ul>
""", '<div class="media-wide"><video src="media/two-phones.mp4" muted loop playsinline preload="metadata"></video></div>')
    + step(2, "家族の「けはい」が見えるリビング", """
<p>アプリのリビングには、家にいる家族が集まります。外出中の人は「おでかけボード」に、寝ている人はソファでうとうと。帰ってくると玄関のドアが開いて「ただいま！」。</p>
<div class="note">🔒 共有するのは「家のWi-Fiにいるか」「スマホを使っているか」だけ。カメラも位置情報も使いません。共有は 家族タブ → けはい・見守り でOFFにできます。</div>
""", vid("arrive"))
    + step(3, "カメラを使わない見守り", """
<p>家族タブで祖父母などの<b>見守り</b>をONにすると、毎朝9時にちびAIが「元気？」とあいさつに行き、返事を家族に伝えます。</p>
<ul><li>「ちょっと調子わるい」→ 急ぎで家族へ</li><li>返事がない・日中4時間スマホに触れていない・電池15%以下 → そっとお知らせ</li><li>見守りは本人にも表示されます（こっそり監視はしません）</li></ul>
""", vid("watch"))
    + step(4, "AIちびっこに頼む", """
<p>「ちびAI」チャンネルで話しかけます。返事の下の「<b>なぜ？</b>」で、使った道具や参照した会話（判断根拠）が見られます。</p>
<ul><li>「電気消して」→ 家電を操作し、リビングの部屋も暗くなる</li><li>「今度の土曜日、みんな空いてる？」→ カレンダーを確認、非公開の人には本人に聞きに行く</li><li>「明日7時にゴミ出しってリマインドして」「前にWi-Fiのパスワードなんて言ってた？」</li></ul>
""", vid("ai"))
    + step(5, "うるさい時はサイレント", """
<p>右上の🌙ボタンから、30分・1時間・3時間・明日の朝まで。ちびっこはお休みし、通知は音なしで残ります。急ぎの連絡だけは届けるかどうかも選べます。</p>
""", vid("silent"))
    + step(6, "横画面・パソコンでも", """
<p>スマホを横にすると、左にリビング・右に会話。パソコンからはブラウザでサーバーのアドレス（例：<code>http://サーバー:8010</code>）を開けば、同じリビングに参加できます。</p>
""", vid("rotate")))

for name, html in pages.items():
    with open(os.path.join(HERE, name), "w") as f:
        f.write(html)
print("built", len(pages), "pages")
