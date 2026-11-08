#!/bin/sh
# macOS 版に同梱する、単体で動く Python を用意する (python-build-standalone + anki ライブラリ)。
# 出来上がる macos/pyruntime は Git 管理外。electron-builder が Resources/python として同梱する。
set -e
cd "$(dirname "$0")"
TAG=20261026
VER=3.12.16
URL="https://github.com/astral-sh/python-build-standalone/releases/download/$TAG/cpython-$VER%2B$TAG-aarch64-apple-darwin-install_only.tar.gz"
rm -rf pyruntime pyruntime-dl && mkdir -p pyruntime pyruntime-dl
curl -sL -o pyruntime-dl/py.tar.gz "$URL"
tar -xzf pyruntime-dl/py.tar.gz -C pyruntime --strip-components=1
rm -rf pyruntime-dl
pyruntime/bin/python3 -m pip install -q anki==26.8.1 Markdown==3.10.3 requests==2.34.2
# 使わないものを外して小さくする (署名するファイルも減る)
rm -rf pyruntime/lib/python3.12/test pyruntime/lib/python3.12/idlelib pyruntime/lib/python3.12/tkinter \
       pyruntime/lib/python3.12/turtledemo pyruntime/lib/python3.12/ensurepip pyruntime/lib/python3.12/site-packages/pip* \
       pyruntime/bin/idle* pyruntime/bin/2to3* pyruntime/bin/pydoc* pyruntime/bin/pip* pyruntime/share pyruntime/include \
       pyruntime/lib/libpython3.12.a
find pyruntime -name "__pycache__" -type d -prune -exec rm -rf {} +
pyruntime/bin/python3 -c "import sys; sys.path.insert(0, 'bridge'); import anki_bridge; print('pyruntime ok', sys.version.split()[0])"
