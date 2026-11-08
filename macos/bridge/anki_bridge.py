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
import os
import shutil
import sqlite3
import sys
import threading
import traceback
from typing import Any

from anki.collection import Collection
from anki.utils import to_json_bytes
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

    # ------------------------------------------------------- iCloud への書き出し

    def mirror(self, src: str, dest: str) -> dict:
        """コレクションとメディアを dest (iCloud Drive のフォルダ) へ写す。変わった分だけ。

        DB は SQLite のバックアップ API で写すので、開いたままでも WAL の内容まで含めて一貫した形になる。
        メディアは名前・大きさ・更新時刻で見比べ、変わったものだけ写し、なくなったものは消す。"""
        os.makedirs(dest, exist_ok=True)
        # 1) コレクション本体
        tmp = os.path.join(dest, "collection.incoming")
        with sqlite3.connect(f"file:{src}?mode=ro", uri=True) as s, sqlite3.connect(tmp) as d:
            s.backup(d)
        os.replace(tmp, os.path.join(dest, "collection.anki2"))
        for side in ("collection.anki2-wal", "collection.anki2-shm"):
            try:
                os.remove(os.path.join(dest, side))
            except FileNotFoundError:
                pass
        # 2) メディア (差分)
        src_media = os.path.join(os.path.dirname(src), "collection.media")
        dst_media = os.path.join(dest, "collection.media")
        os.makedirs(dst_media, exist_ok=True)
        have = {}
        for e in os.scandir(dst_media):
            if e.is_file():
                st = e.stat()
                have[e.name] = (st.st_size, int(st.st_mtime))
        copied = removed = 0
        seen = set()
        if os.path.isdir(src_media):
            for e in os.scandir(src_media):
                if not e.is_file() or e.name.startswith("."):
                    continue
                seen.add(e.name)
                st = e.stat()
                if have.get(e.name) == (st.st_size, int(st.st_mtime)):
                    continue
                shutil.copy2(e.path, os.path.join(dst_media, e.name))
                copied += 1
        for name in have:
            if name not in seen and not name.startswith("."):
                try:
                    os.remove(os.path.join(dst_media, name))
                    removed += 1
                except OSError:
                    pass
        return {"copied": copied, "removed": removed, "total": len(seen)}

    # ---------------------------------------------- iCloud (iPhone) からの取り込み

    @staticmethod
    def _mirror_db(dest: str) -> sqlite3.Connection:
        path = os.path.join(dest, "collection.anki2")
        if not os.path.exists(path):
            raise RuntimeError("同期フォルダに collection.anki2 がありません")
        return sqlite3.connect(f"file:{path}?mode=ro", uri=True)

    @staticmethod
    def _proto_strings(blob: bytes) -> dict:
        """protobuf の length-delimited フィールドを 番号 → 文字列 で集める (ノートタイプの CSS やテンプレート用)"""
        out, i, n = {}, 0, len(blob)
        def varint():
            nonlocal i
            r, sh = 0, 0
            while i < n:
                b = blob[i]; i += 1
                r |= (b & 0x7F) << sh
                if not b & 0x80:
                    return r
                sh += 7
            return None
        while i < n:
            key = varint()
            if key is None:
                break
            f, w = key >> 3, key & 7
            if w == 0:
                varint()
            elif w == 1:
                i += 8
            elif w == 5:
                i += 4
            elif w == 2:
                ln = varint()
                if ln is None or i + ln > n:
                    break
                try:
                    out[f] = blob[i:i + ln].decode("utf-8")
                except UnicodeDecodeError:
                    pass
                i += ln
            else:
                break
        return out

    def _changed_notetypes(self, m: sqlite3.Connection) -> list:
        """iPhone 側で更新された (mtime がこちらより新しい) か、こちらにないノートタイプの id"""
        col = self._col()
        mine = {r[0]: r[1] for r in col.db.all("select id, mtime_secs from notetypes")}
        return [r[0] for r in m.execute("select id, mtime_secs from notetypes") if r[0] not in mine or r[1] > mine[r[0]]]

    def _removed_notetypes(self, m: sqlite3.Connection) -> list:
        """iPhone 側で消されたノートタイプ (こちらにあって向こうにない)。カードが使っているものは残す"""
        col = self._col()
        theirs = {r[0] for r in m.execute("select id from notetypes")}
        out = []
        for (ntid,) in col.db.all("select id from notetypes"):
            if ntid not in theirs and not col.db.scalar("select 1 from notes where mid = ? limit 1", ntid):
                out.append(ntid)
        return out

    def _pull_notetypes(self, m: sqlite3.Connection, ids: list) -> int:
        """iPhone 側のノートタイプ (名前・欄・テンプレート・CSS) を Anki のコレクションへ反映する。
        欄の並べ替え・追加・削除は Anki の update_dict に任せ、ノートの本文の並べ替えも Anki が行う
        (そのあと iPhone 側のノートが取り込まれて同じ内容になる)"""
        col = self._col()
        done = 0
        for ntid in ids:
            name, cfg = m.execute("select name, config from notetypes where id = ?", (ntid,)).fetchone()
            their_fields = [r[0] for r in m.execute("select name from fields where ntid = ? order by ord", (ntid,))]
            their_tmpls = {r[0]: (r[1], r[2]) for r in m.execute("select ord, name, config from templates where ntid = ?", (ntid,))}
            nt = col.models.get(ntid)
            if not nt:
                # iPhone 側で新しく作られたノートタイプ。同じ id で作る (ノートの mid がそのまま使えるように)
                nt = col.models.new(name)
                nt["id"] = ntid
                for fname in their_fields:
                    nt["flds"].append(col.models.new_field(fname))
                for ord_ in sorted(their_tmpls):
                    tname, tcfg = their_tmpls[ord_]
                    strs = self._proto_strings(tcfg)
                    t = col.models.new_template(tname)
                    t["qfmt"] = strs.get(1, "")
                    t["afmt"] = strs.get(2, "")
                    nt["tmpls"].append(t)
                nt["css"] = self._proto_strings(cfg).get(3, nt["css"])
                col._backend.add_or_update_notetype(json=to_json_bytes(nt), preserve_usn_and_mtime=False, skip_checks=False)
                done += 1
                continue
            # 欄: 同じ名前の欄はそのまま (ord を保つので Anki が本文を正しく並べ替える)、新しい名前は足す
            by_name = {f["name"]: f for f in nt["flds"]}
            flds = []
            for fname in their_fields:
                if fname in by_name:
                    flds.append(by_name[fname])
                else:
                    flds.append(col.models.new_field(fname))
            nt["flds"] = flds
            # テンプレート (数は変えない)
            for t in nt["tmpls"]:
                if t["ord"] in their_tmpls:
                    tname, tcfg = their_tmpls[t["ord"]]
                    strs = self._proto_strings(tcfg)
                    t["name"] = tname
                    t["qfmt"] = strs.get(1, t["qfmt"])
                    t["afmt"] = strs.get(2, t["afmt"])
            nt["css"] = self._proto_strings(cfg).get(3, nt["css"])
            nt["name"] = name
            col.models.update_dict(nt)
            done += 1
        return done

    def diffMirror(self, dest: str) -> dict:
        """Anki のコレクションと iCloud 側 (iPhone が書いたもの) の違いを調べる。

        conflicts: 両方にあって本文かタグが違い、iPhone 側の方が新しいノート (どちらを残すか選んでもらう)
        auto:      そのまま取り込める変更の数 (iPhone で作ったノート、消したノート、学習の記録・デッキ移動)"""
        col = self._col()
        m = self._mirror_db(dest)
        try:
            theirs = {r[0]: r for r in m.execute("select id, mod, flds, tags, mid from notes")}
            their_cards = {r[0]: r for r in m.execute("select id, nid, mod from cards")}
            their_graves = {r[0] for r in m.execute("select oid from graves where type = 1")}
            their_revlog = {r[0] for r in m.execute("select id from revlog")}
        finally:
            m.close()
        mine = {r[0]: r for r in col.db.all("select id, mod, flds, tags, mid from notes")}
        my_revlog = {r[0] for r in col.db.all("select id from revlog")}
        my_cards = {r[0]: r for r in col.db.all("select id, nid, mod from cards")}
        # ノートタイプの欄が変わっていると本文の区切りも変わる。欄の名前ごとに比べて、同じなら食い違いとしない
        m3 = self._mirror_db(dest)
        try:
            changed_nt = set(self._changed_notetypes(m3))
            their_fnames = {}
            for ntid, ord_, fname in m3.execute("select ntid, ord, name from fields order by ntid, ord"):
                their_fnames.setdefault(ntid, []).append(fname)
        finally:
            m3.close()
        my_fnames = {nt["id"]: [f["name"] for f in nt["flds"]] for nt in col.models.all()}
        def same_by_name(mid, mine_flds, their_flds):
            a = dict(zip(my_fnames.get(mid, []), mine_flds.split("\x1f")))
            b = dict(zip(their_fnames.get(mid, []), their_flds.split("\x1f")))
            common = set(a) & set(b)
            return bool(common) and all(a[k] == b[k] for k in common) and all(not v for k, v in b.items() if k not in a)

        conflicts = []
        mac_newer = 0
        for nid, t in theirs.items():
            me = mine.get(nid)
            if me is None:
                continue
            if me[2] == t[2] and me[3].strip() == t[3].strip():
                continue
            if t[4] in changed_nt and me[3].strip() == t[3].strip() and same_by_name(t[4], me[2], t[2]):
                continue   # 欄の構成が変わっただけ (ノートタイプの取り込みでそろう)
            if t[1] > me[1]:
                nt = col.models.get(me[4])
                names = [f["name"] for f in nt["flds"]] if nt else []
                conflicts.append({
                    "id": nid,
                    "mine": {"fields": me[2].split("\x1f"), "tags": me[3].strip(), "fieldNames": names,
                             "title": me[2].split("\x1f")[0][:80], "savedAt": me[1]},
                    "theirs": {"fields": t[2].split("\x1f"), "tags": t[3].strip(), "mtimeSecs": t[1]},
                })
            else:
                mac_newer += 1
        new_notes = [nid for nid in theirs if nid not in mine]
        deleted = [nid for nid in mine if nid not in theirs and nid in their_graves]
        card_updates = [cid for cid, c in their_cards.items() if cid in my_cards and c[2] > my_cards[cid][2]]
        new_revlog = len(their_revlog - my_revlog)
        m2 = self._mirror_db(dest)
        try:
            nt_changed = len(self._changed_notetypes(m2))
        finally:
            m2.close()
        return {
            "conflicts": conflicts,
            "auto": {"newNotes": len(new_notes), "deleted": len(deleted), "cards": len(card_updates), "revlog": new_revlog, "notetypes": nt_changed},
            "macNewer": mac_newer,
        }

    def pullMirror(self, dest: str, choices: dict | None = None, theirsAll: bool = False) -> dict:
        """iCloud 側の変更を Anki のコレクションへ取り込む。

        choices: {noteId: "theirs" | "mine"} 競合ノートの選択。theirsAll なら全部 iPhone 側を採る。
        選ばれなかった (mine) ノートは何もしない (あとで mirror() が Anki 側の内容で iCloud を上書きする)。"""
        col = self._col()
        choices = choices or {}
        m = self._mirror_db(dest)
        # 0) ノートタイプの変更 (欄・テンプレート・CSS) を先に反映する。ノートの本文はそのあとで写す
        notetypes = self._pull_notetypes(m, self._changed_notetypes(m))
        for ntid in self._removed_notetypes(m):
            col.models.remove(ntid)
            notetypes += 1
        try:
            theirs = {r[0]: r for r in m.execute(
                "select id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data from notes")}
            their_cards = {r[0]: r for r in m.execute(
                "select id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data from cards")}
            their_graves = {r[0] for r in m.execute("select oid from graves where type = 1")}
            their_revlog = list(m.execute("select id, cid, usn, ease, ivl, lastIvl, factor, time, type from revlog"))
        finally:
            m.close()
        mine = {r[0]: r for r in col.db.all("select id, mod, flds, tags from notes")}
        my_cards = {r[0]: r[1] for r in col.db.all("select id, mod from cards")}
        my_revlog = {r[0] for r in col.db.all("select id from revlog")}
        known_models = {m["id"] for m in col.models.all()}
        known_decks = {d["id"] for d in col.decks.all()}

        added = updated = deleted = cards = revlog = 0
        # 1) iPhone で作ったノート (そのまま行を入れる。id・guid を保つ)
        for nid, t in theirs.items():
            if nid in mine or t[2] not in known_models:
                continue
            col.db.execute(
                "insert into notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) values (?,?,?,?,?,?,?,?,?,?,?)",
                nid, t[1], t[2], t[3], -1, t[5], t[6], t[7], t[8], t[9], t[10])
            for c in their_cards.values():
                if c[1] != nid:
                    continue
                did = c[2] if c[2] in known_decks else 1
                col.db.execute(
                    "insert into cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    c[0], c[1], did, c[3], c[4], -1, c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14], c[15], c[16], c[17])
                my_cards[c[0]] = c[4]
            added += 1
        # 2) 本文・タグの違い: 選ばれたもの (または iPhone 側が新しいもの) を採る
        for nid, t in theirs.items():
            me = mine.get(nid)
            if me is None:
                continue
            if me[2] == t[6] and me[3].strip() == t[5].strip():
                continue
            pick = choices.get(str(nid)) or choices.get(nid)
            if pick is None:
                pick = "theirs" if (theirsAll or t[3] > me[1]) else "mine"
            if pick != "theirs":
                continue
            note = col.get_note(nid)
            note.fields = t[6].split("\x1f")
            note.tags = col.tags.split(t[5])
            col.update_note(note)
            updated += 1
        # 3) iPhone で消したノート
        gone = [nid for nid in mine if nid not in theirs and nid in their_graves]
        if gone:
            col.remove_notes(gone)
            deleted = len(gone)
        # 4) 学習の記録・デッキ移動 (iPhone 側の方が新しいカード)
        for cid, c in their_cards.items():
            if cid not in my_cards or c[4] <= my_cards[cid]:
                continue
            did = c[2] if c[2] in known_decks else None
            col.db.execute(
                "update cards set did = coalesce(?, did), mod = ?, usn = -1, type = ?, queue = ?, due = ?, ivl = ?, factor = ?, reps = ?, lapses = ?, left = ?, flags = ? where id = ?",
                did, c[4], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[16], cid)
            cards += 1
        for r in their_revlog:
            if r[0] in my_revlog or r[1] not in my_cards:
                continue
            col.db.execute("insert into revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) values (?,?,?,?,?,?,?,?,?)",
                           r[0], r[1], -1, r[3], r[4], r[5], r[6], r[7], r[8])
            revlog += 1
        return {"added": added, "updated": updated, "deleted": deleted, "cards": cards, "revlog": revlog, "notetypes": notetypes}

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
