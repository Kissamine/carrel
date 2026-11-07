# 同梱しているソフトウェアとライセンス

Carrel は次のソフトウェアを同梱・利用しています。それぞれのライセンスに従います。

| ソフトウェア | 用途 | ライセンス |
|---|---|---|
| [Anki](https://github.com/ankitects/anki) (Python パッケージ `anki`) | コレクションの読み書き (同期機能は使わない) | AGPL-3.0-or-later |
| [Electron](https://www.electronjs.org/) | アプリの土台 | MIT |
| [CodeMirror 6](https://codemirror.net/) | HTML エディタ | MIT |
| [MathJax 3](https://www.mathjax.org/) | 数式の組版 | Apache-2.0 |
| [jQuery](https://jquery.com/) | カード内スクリプトの互換用 | MIT |
| [esbuild](https://esbuild.github.io/) | ビルド時のバンドル | MIT |
| Python 3 と `protobuf` `orjson` `requests` | `anki` の実行環境 | PSF-2.0 / BSD-3 / Apache-2.0 / Apache-2.0 |

Anki は Ankitects Pty Ltd の商標です。Carrel は Ankitects とは無関係の個人プロジェクトで、
Ankitects の承認や保証を受けたものではありません。Carrel は Anki のコレクション形式を `anki` ライブラリ経由で読み書きするだけで、
AnkiWeb には接続しません (AnkiWeb の利用規約は承認されたクライアント以外の接続を認めていないため)。
