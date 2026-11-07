"""
Anki Editor (macOS) のバックエンド。

公式の Python 版 anki をそのまま使い、1 行 1 JSON のリクエスト / レスポンスで
Kotlin 側 (Compose Desktop) とやり取りする。Android 版が rsdroid 経由で呼んでいる
API と同じものを、同じ名前で並べてある。

  リクエスト:  {"id": 1, "method": "searchNotes", "params": {...}}
  レスポンス:  {"id": 1, "ok": true, "result": ...}
              {"id": 1, "ok": false, "error": "..."}
"""

import base64
import json
import sys
import threading
import traceback
from typing import Any

from anki.collection import Collection
from anki.sync import SyncAuth


class Bridge:
    def __init__(self) -> None:
        self.col: Collection | None = None
        self._job: dict | None = None
        self._job_lock = threading.Lock()

    # ------------------------------------------------------------ 開く / 閉じる

    def open(self, path: str) -> dict:
        if self.col is None:
            self.col = Collection(path)
        return {"path": self.col.path, "mediaDir": self.col.media.dir()}

    def close(self) -> None:
        if self.col is not None:
            self.col.close()
            self.col = None

    def _col(self) -> Collection:
        if self.col is None:
            raise RuntimeError("コレクションが開かれていません")
        return self.col

    # ------------------------------------------------------------------ 同期

    @staticmethod
    def _auth(d: dict) -> SyncAuth:
        auth = SyncAuth()
        auth.hkey = d["hkey"]
        # endpoint は未設定と空文字が区別される。AnkiWeb では未設定のままにする
        if d.get("endpoint"):
            auth.endpoint = d["endpoint"]
        return auth

    @staticmethod
    def _auth_json(auth: SyncAuth) -> dict:
        return {
            "hkey": auth.hkey,
            "endpoint": auth.endpoint if auth.HasField("endpoint") else None,
        }

    def login(self, username: str, password: str) -> dict:
        auth = self._col().sync_login(username, password, None)
        return self._auth_json(auth)

    def syncCollection(self, auth: dict) -> dict:
        out = self._col().sync_collection(self._auth(auth), False)
        # AnkiWeb はユーザーごとに接続先 (シャード) を割り当てて返す
        merged = dict(auth)
        if out.new_endpoint:
            merged["endpoint"] = out.new_endpoint
        required = {
            out.NO_CHANGES: "NONE",
            out.NORMAL_SYNC: "NONE",
            out.FULL_SYNC: "DOWNLOAD",
            out.FULL_DOWNLOAD: "DOWNLOAD",
            out.FULL_UPLOAD: "UPLOAD",
        }.get(out.required, "NONE")
        return {"required": required, "auth": merged}

    def fullSync(self, auth: dict, upload: bool) -> dict:
        """
        コレクション全体の送受信。時間がかかるので別のスレッドで走らせ、
        呼び出し側は jobStatus で様子を見る (待っている間も他の問い合わせに答えられる)。
        """
        col = self._col()
        auth_obj = self._auth(auth)

        def run() -> None:
            col.full_upload_or_download(auth=auth_obj, server_usn=None, upload=upload)

        return self._start_job("fullSync", run)

    def syncMedia(self, auth: dict) -> dict:
        """
        メディア同期を始める。実体は Rust 側が裏で走らせるので、ここでは待たない。
        進み具合は mediaStatus で見る。
        """
        self._col().sync_media(self._auth(auth))
        return self.mediaStatus()

    def mediaStatus(self) -> dict:
        status = self._col().media_sync_status()
        return {
            "active": status.active,
            "checked": status.progress.checked,
            "added": status.progress.added,
            "removed": status.progress.removed,
        }

    # ------------------------------------------------------- 時間のかかる処理

    def _start_job(self, name: str, run) -> dict:
        with self._job_lock:
            if self._job and self._job["running"]:
                return dict(self._job)
            self._job = {"name": name, "running": True, "error": None}

        def wrapper() -> None:
            try:
                run()
            except Exception as e:  # 失敗は jobStatus で伝える
                with self._job_lock:
                    self._job["error"] = f"{e}"
            finally:
                with self._job_lock:
                    self._job["running"] = False

        threading.Thread(target=wrapper, daemon=True).start()
        with self._job_lock:
            return dict(self._job)

    def jobStatus(self) -> dict:
        """実行中の処理の様子。full_sync のときは転送量も返す"""
        with self._job_lock:
            state = dict(self._job) if self._job else {"running": False, "error": None}
        progress = self._col().latest_progress()
        if progress.HasField("full_sync"):
            state["transferred"] = progress.full_sync.transferred
            state["total"] = progress.full_sync.total
        return state

    # ------------------------------------------------------------- 参照 / 検索

    def decks(self) -> list[str]:
        return sorted(d.name for d in self._col().decks.all_names_and_ids())

    @staticmethod
    def _note_json(note) -> dict:
        return {
            "id": note.id,
            "modelId": note.mid,
            "fields": list(note.fields),
            "tags": " ".join(note.tags),
            "mtimeSecs": note.mod,
        }

    def searchNotes(self, query: str, limit: int = 200, brief: int = 0) -> list[dict]:
        """
        一覧に出すためのノート一覧。

        brief を立てると各フィールドを先頭だけに切り詰めて返す。
        一覧では見出しとタグしか使わないので、長い本文まで送ると
        受け取り側の解析だけで数秒かかってしまう。
        """
        col = self._col()
        ids = col.find_notes(query or "deck:*")
        out = []
        for nid in ids[:limit]:
            try:
                note = col.get_note(nid)
            except Exception:
                continue
            data = self._note_json(note)
            if brief:
                data["fields"] = [f[:400] for f in data["fields"]]
                data["brief"] = True
            out.append(data)
        return out

    def allTags(self) -> list[str]:
        return sorted(self._col().tags.all())

    def fieldNames(self, notetypeId: int) -> list[str]:
        nt = self._col().models.get(notetypeId)
        return [f["name"] for f in nt["flds"]] if nt else []

    def noteContent(self, id: int) -> dict | None:
        try:
            note = self._col().get_note(id)
        except Exception:
            return None
        return {"fields": list(note.fields), "tags": " ".join(note.tags)}

    # ------------------------------------------------------------------ 描画

    def _with_audio(self, html: str) -> str:
        col = self._col()
        tags = col._backend.extract_av_tags(text=html, question_side=False)
        out = tags.text
        for index, tag in enumerate(tags.av_tags):
            name = tag.sound_or_video if tag.HasField("sound_or_video") else None
            player = (
                ""
                if not name
                else f'<audio controls preload="none" src="{name}" '
                'style="display:block;width:100%;max-width:320px;margin:.6em 0;"></audio>'
            )
            out = out.replace(f"[anki:play:q:{index}]", player)
            out = out.replace(f"[anki:play:a:{index}]", player)
        return out

    def renderNote(self, id: int) -> dict:
        col = self._col()
        note = col.get_note(id)
        cards = note.cards()
        if not cards:
            return {"body": None, "css": "", "error": "このノートにカードがありません"}
        output = cards[0].render_output(reload=True)
        nt = col.models.get(note.mid)
        return {
            "body": self._with_audio(output.answer_text),
            "css": nt["css"] if nt else "",
        }

    def renderDraft(self, id: int, modelId: int, fields: list, tags: str) -> dict:
        col = self._col()
        nt = col.models.get(modelId)
        if not nt or not nt["tmpls"]:
            return {"body": None, "css": "", "error": "テンプレートがありません"}
        note = col.get_note(id) if id else col.new_note(nt)
        note.fields = list(fields)
        note.tags = tags.split()
        # テンプレートはバックエンド側の proto をそのまま渡す
        template = col._backend.get_notetype(ntid=modelId).templates[0]
        output = col._backend.render_uncommitted_card(
            note=note._to_backend_note(),
            card_ord=0,
            template=template,
            fill_empty=False,
            partial_render=False,
        )
        answer = "".join(
            n.text if n.HasField("text") else n.replacement.current_text
            for n in output.answer_nodes
        )
        return {"body": self._with_audio(answer), "css": nt["css"]}

    # ------------------------------------------------------------------ 編集

    def saveNote(self, id: int, fields: list, tags: str) -> None:
        col = self._col()
        note = col.get_note(id)
        note.fields = list(fields)
        note.tags = tags.split()
        col.update_note(note)

    def replaceInNotes(
        self, query: str, find: str, repl: str, regex: int = 0, matchCase: int = 1
    ) -> dict:
        """すべてのノート (または絞り込んだノート) の中身をまとめて置き換える"""
        col = self._col()
        ids = col.find_notes(query or "")
        if not ids:
            return {"notes": 0, "hits": 0}
        changed = col.find_and_replace(
            note_ids=list(ids),
            search=find,
            replacement=repl,
            regex=bool(regex),
            field_name=None,
            match_case=bool(matchCase),
        )
        count = getattr(changed, "count", 0)
        return {"notes": int(count), "hits": int(count), "scanned": len(ids)}

    def addingDefaults(self) -> dict:
        d = self._col().defaults_for_adding(current_review_card=None)
        return {"deckId": d.deck_id, "notetypeId": d.notetype_id}

    def deckId(self, name: str) -> int | None:
        return self._col().decks.id_for_name(name)

    def addNote(self, notetypeId: int, deckId: int, fields: list, tags: str) -> dict:
        col = self._col()
        nt = col.models.get(notetypeId)
        note = col.new_note(nt)
        note.fields = list(fields)
        note.tags = tags.split()
        col.add_note(note, deckId)
        return self._note_json(col.get_note(note.id))

    def deleteNotes(self, ids: list) -> int:
        return self._col().remove_notes(ids).count

    def addMedia(self, name: str, dataBase64: str) -> str:
        return self._col().media.write_data(name, base64.b64decode(dataBase64))

    # -------------------------------------------------------- ノートタイプ編集

    def notetypes(self) -> list[dict]:
        col = self._col()
        out = []
        for nt in col.models.all_names_and_ids():
            count = col.db.scalar("select count() from notes where mid = ?", nt.id) or 0
            out.append({"id": nt.id, "name": nt.name, "notes": count})
        # よく使うものを先に
        out.sort(key=lambda x: -x["notes"])
        return out

    def templateOf(self, notetypeId: int) -> dict | None:
        nt = self._col().models.get(notetypeId)
        if not nt or not nt["tmpls"]:
            return None
        t = nt["tmpls"][0]
        return {
            "notetypeId": notetypeId,
            "name": nt["name"],
            "front": t["qfmt"],
            "back": t["afmt"],
            "css": nt["css"],
        }

    def saveTemplate(self, notetypeId: int, front: str, back: str, css: str) -> None:
        col = self._col()
        nt = col.models.get(notetypeId)
        if not nt or not nt["tmpls"]:
            return
        nt["tmpls"][0]["qfmt"] = front
        nt["tmpls"][0]["afmt"] = back
        nt["css"] = css
        col.models.update_dict(nt)

    # ---------------------------------------------------------- メディア整理

    def unusedMedia(self) -> list[dict]:
        import os

        col = self._col()
        report = col.media.check()
        out = []
        for name in report.unused:
            path = os.path.join(col.media.dir(), name)
            size = os.path.getsize(path) if os.path.isfile(path) else 0
            out.append({"name": name, "sizeBytes": size})
        return out

    def mediaReport(self) -> dict:
        """メディア全体の様子: 未使用 (大きさ付き)・見つからないもの・総数"""
        import os

        col = self._col()
        report = col.media.check()
        folder = col.media.dir()
        unused = []
        for name in report.unused:
            path = os.path.join(folder, name)
            size = os.path.getsize(path) if os.path.isfile(path) else 0
            unused.append({"name": name, "sizeBytes": size})
        total = 0
        total_bytes = 0
        for entry in os.scandir(folder):
            if entry.is_file() and not entry.name.startswith("."):
                total += 1
                total_bytes += entry.stat().st_size
        return {
            "unused": unused,
            "missing": list(report.missing),
            "total": total,
            "totalBytes": total_bytes,
        }

    # ------------------------------------------------------ 控えからの復元

    def backupNotes(self, path: str, query: str = "", limit: int = 200) -> list[dict]:
        """控えのファイルを開いて、中のノートを一覧で返す (読むだけ)"""
        from anki.collection import Collection

        other = Collection(path)
        try:
            ids = other.find_notes(query or "deck:*")
            out = []
            for nid in ids[:limit]:
                try:
                    note = other.get_note(nid)
                except Exception:
                    continue
                out.append({
                    "id": nid,
                    "fields": [f[:400] for f in note.fields],
                    "sizes": [len(f) for f in note.fields],
                    "tags": " ".join(note.tags),
                    "mtimeSecs": note.mod,
                })
            return out
        finally:
            other.close()

    def restoreNote(self, path: str, id: int) -> dict:
        """控えの中のそのノートを、いまのコレクションへ書き戻す"""
        from anki.collection import Collection

        other = Collection(path)
        try:
            source = other.get_note(id)
            fields = list(source.fields)
            tags = list(source.tags)
        finally:
            other.close()

        col = self._col()
        note = col.get_note(id)
        before = [len(f) for f in note.fields]
        note.fields = fields
        note.tags = tags
        col.update_note(note)
        return {"before": before, "after": [len(f) for f in fields]}

    def deleteMedia(self, names: list) -> None:
        if names:
            self._col().media.trash_files(list(names))


def main() -> None:
    bridge = Bridge()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            continue
        rid = request.get("id")
        method = request.get("method", "")
        params: dict[str, Any] = request.get("params") or {}
        try:
            fn = getattr(bridge, method, None)
            if fn is None or method.startswith("_"):
                raise RuntimeError(f"不明なメソッド: {method}")
            result = fn(**params)
            response = {"id": rid, "ok": True, "result": result}
        except Exception as e:  # バックエンドの例外はそのまま伝える
            response = {
                "id": rid,
                "ok": False,
                "error": f"{e}",
                "trace": traceback.format_exc(limit=3),
            }
        sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
