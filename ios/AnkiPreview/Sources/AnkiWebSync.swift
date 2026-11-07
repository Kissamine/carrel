import Foundation
import Security

/// AnkiWeb から取ってくるだけの同期 (アップロードはしない)。
/// 旧形式 (multipart、圧縮なし) で話すので zstd がいらない。コレクションはスキーマ 11 で届く
final class AnkiWebClient: NSObject, URLSessionTaskDelegate {
    static let endpoint = URL(string: "https://sync.ankiweb.net/")!
    /// サーバが古いクライアントをはねないよう、Anki 本体と同じ形の名前を名乗る
    static let clientVersion = "anki,2.1.66 (70506aeb),mac"

    var hostKey: String
    private let session: String
    private lazy var urlSession: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 60
        cfg.timeoutIntervalForResource = 60 * 60
        return URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
    }()

    init(hostKey: String) {
        self.hostKey = hostKey
        self.session = String((0..<8).map { _ in "abcdefghijklmnopqrstuvwxyz0123456789".randomElement()! })
    }

    struct SyncError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// リダイレクトされても POST と本文を保つ (URLSession は既定で GET に変えてしまう)
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        var r = request
        r.httpMethod = task.originalRequest?.httpMethod
        r.httpBody = task.originalRequest?.httpBody
        r.allHTTPHeaderFields = task.originalRequest?.allHTTPHeaderFields
        completionHandler(r)
    }

    /// multipart で送って、生の応答を受け取る
    private func post(_ path: String, fields: [String: String], data: Data, toFile: Bool = false) async throws -> (Data, URL?) {
        let boundary = "----AnkiEditor\(UUID().uuidString)"
        var body = Data()
        func part(_ name: String, _ value: String) {
            body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".data(using: .utf8)!)
        }
        part("c", "0")
        for (k, v) in fields { part(k, v) }
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"data\"; filename=\"data\"\r\nContent-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)

        var req = URLRequest(url: Self.endpoint.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.setValue(Self.clientVersion, forHTTPHeaderField: "User-Agent")
        req.httpBody = body

        let resp: URLResponse
        var out = Data()
        var file: URL? = nil
        if toFile {
            let (url, r) = try await urlSession.download(for: req)
            resp = r; file = url
        } else {
            let (d, r) = try await urlSession.data(for: req)
            resp = r; out = d
        }
        guard let http = resp as? HTTPURLResponse else { throw SyncError(message: "応答がありません") }
        switch http.statusCode {
        case 200: return (out, file)
        case 403: throw SyncError(message: "AnkiWeb のメールアドレスかパスワードが違います")
        case 409: throw SyncError(message: "ほかの端末が同期中です。少し待ってからやり直してください")
        case 500...: throw SyncError(message: "AnkiWeb 側でエラーが起きました (\(http.statusCode))")
        default:
            let text = String(data: out, encoding: .utf8) ?? ""
            throw SyncError(message: "AnkiWeb の応答 \(http.statusCode) \(text.prefix(200))")
        }
    }

    private func json(_ obj: Any) throws -> Data { try JSONSerialization.data(withJSONObject: obj) }

    // ---- コレクション ----

    /// メールアドレスとパスワードでログインして、以後に使う鍵をもらう
    static func login(username: String, password: String) async throws -> String {
        let c = AnkiWebClient(hostKey: "")
        let (data, _) = try await c.post("sync/hostKey", fields: [:], data: try c.json(["u": username, "p": password]))
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any], let key = obj["key"] as? String, !key.isEmpty else {
            throw SyncError(message: "ログインの応答が読めません")
        }
        return key
    }

    /// サーバの状態。続けてよいか (cont) と、だめなときの理由 (msg)
    func meta() async throws -> [String: Any] {
        let (data, _) = try await post("sync/meta", fields: ["k": hostKey, "s": session],
                                       data: try json(["v": 10, "cv": Self.clientVersion]))
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw SyncError(message: "meta の応答が読めません") }
        if let cont = obj["cont"] as? Bool, !cont {
            throw SyncError(message: (obj["msg"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "AnkiWeb が同期を受け付けませんでした")
        }
        return obj
    }

    /// コレクション全体をファイルに落とす
    func downloadCollection() async throws -> URL {
        let (_, file) = try await post("sync/download", fields: ["k": hostKey, "s": session], data: "{}".data(using: .utf8)!, toFile: true)
        guard let file else { throw SyncError(message: "ダウンロードに失敗しました") }
        return file
    }

    // ---- メディア ----

    private func unwrap(_ data: Data) throws -> Any {
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw SyncError(message: "メディアの応答が読めません") }
        if let err = obj["err"] as? String, !err.isEmpty { throw SyncError(message: err) }
        return obj["data"] as Any
    }

    /// メディア同期の開始。サーバ側の最新 usn を返す
    func mediaBegin() async throws -> Int {
        let (data, _) = try await post("msync/begin", fields: ["k": hostKey, "v": Self.clientVersion], data: "{}".data(using: .utf8)!)
        guard let d = try unwrap(data) as? [String: Any], let usn = d["usn"] as? Int else { throw SyncError(message: "メディア同期を始められません") }
        return usn
    }

    struct MediaChange { let name: String; let usn: Int; let sha1: String }

    /// lastUsn より後に変わったファイル (sha1 が空なら削除)
    func mediaChanges(since lastUsn: Int) async throws -> [MediaChange] {
        let (data, _) = try await post("msync/mediaChanges", fields: ["k": hostKey, "v": Self.clientVersion], data: try json(["lastUsn": lastUsn]))
        guard let rows = try unwrap(data) as? [[Any]] else { throw SyncError(message: "変更一覧が読めません") }
        return rows.compactMap { r in
            guard r.count >= 3, let n = r[0] as? String, let u = r[1] as? Int else { return nil }
            return MediaChange(name: n, usn: u, sha1: (r[2] as? String) ?? "")
        }
    }

    /// ファイルをまとめて (最大 25 件) もらう。名前 → 中身
    func downloadFiles(_ names: [String]) async throws -> [String: Data] {
        let (data, _) = try await post("msync/downloadFiles", fields: ["k": hostKey, "v": Self.clientVersion], data: try json(["files": names]))
        let entries = try StoredZip.entries(data)
        guard let metaData = entries["_meta"], let meta = try JSONSerialization.jsonObject(with: metaData) as? [String: String] else {
            throw SyncError(message: "メディアの zip が読めません")
        }
        var out: [String: Data] = [:]
        for (idx, real) in meta { if let d = entries[idx] { out[real] = d } }
        return out
    }
}

/// 無圧縮 (stored) の zip だけ読む小さな読み取り
enum StoredZip {
    static func entries(_ d: Data) throws -> [String: Data] {
        let bytes = [UInt8](d)
        func u16(_ i: Int) -> Int { Int(bytes[i]) | Int(bytes[i + 1]) << 8 }
        func u32(_ i: Int) -> Int { u16(i) | u16(i + 2) << 16 }
        // 末尾の End of Central Directory を探す
        var eocd = -1
        var i = bytes.count - 22
        while i >= 0 && i >= bytes.count - 22 - 65536 {
            if bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x05 && bytes[i + 3] == 0x06 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw AnkiWebClient.SyncError(message: "zip の終端がありません") }
        let count = u16(eocd + 10)
        var p = u32(eocd + 16)
        var out: [String: Data] = [:]
        for _ in 0..<count {
            guard p + 46 <= bytes.count, u32(p) == 0x02014b50 else { break }
            let method = u16(p + 10)
            let csize = u32(p + 20)
            let nlen = u16(p + 28), elen = u16(p + 30), clen = u16(p + 32)
            let local = u32(p + 42)
            let name = String(decoding: bytes[(p + 46)..<(p + 46 + nlen)], as: UTF8.self)
            p += 46 + nlen + elen + clen
            guard method == 0, local + 30 <= bytes.count, u32(local) == 0x04034b50 else { continue }
            let lnlen = u16(local + 26), lelen = u16(local + 28)
            let start = local + 30 + lnlen + lelen
            guard start + csize <= bytes.count else { continue }
            out[name] = Data(bytes[start..<(start + csize)])
        }
        return out
    }
}

/// AnkiWeb の鍵をキーチェーンに入れておく (パスワードは保存しない)
enum Keychain {
    private static let service = "com.haru.ankieditor.ankiweb"
    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: key, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
    static func set(_ key: String, _ value: String?) {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
        SecItemDelete(base as CFDictionary)
        guard let value, let d = value.data(using: .utf8) else { return }
        var q = base
        q[kSecValueData as String] = d
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(q as CFDictionary, nil)
    }
}
