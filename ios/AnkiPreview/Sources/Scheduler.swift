import Foundation

/// Anki の SM-2 系スケジューラに合わせた、簡単な学習の進め方。
/// カードの欄 (queue / type / due / ivl / factor / reps / lapses / left) を Anki と同じ意味で書くので、
/// ここで学習した結果を Anki 本体に戻しても、そのまま続きができる。
enum Scheduler {
    /// 学習ステップ (分)。Anki の既定と同じ
    static let learningSteps: [Int] = [1, 10]
    static let relearningSteps: [Int] = [10]
    static let graduatingInterval = 1      // 日
    static let easyInterval = 4            // 日
    static let maxInterval = 36500
    static let minFactor = 1300

    enum Ease: Int, CaseIterable { case again = 1, hard = 2, good = 3, easy = 4
        var label: String { switch self { case .again: "もう一度"; case .hard: "難しい"; case .good: "普通"; case .easy: "簡単" } }
    }

    /// 復習の記録に書く種類: 0 学習 / 1 復習 / 2 再学習
    struct Result { var card: CardInfo; var revlogType: Int; var revlogIvl: Int; var lastIvl: Int }

    /// コレクション作成からの日数 (Anki の「今日」)。crt は秒
    static func today(crt: Int64, now: Date = Date()) -> Int64 {
        max(0, Int64((now.timeIntervalSince1970 - Double(crt)) / 86400))
    }

    /// 復習が必要か (学習中は時刻、復習は日数で比べる)
    static func isDue(_ c: CardInfo, crt: Int64, now: Date = Date()) -> Bool {
        switch c.queue {
        case 0: return true
        case 1: return Double(c.due) <= now.timeIntervalSince1970
        case 2, 3: return c.due <= today(crt: crt, now: now)
        default: return false
        }
    }

    /// 各ボタンを押したときの次の間隔の見せ方 ("10分" "1日" "3.2か月" など)
    static func preview(_ c: CardInfo, ease: Ease, crt: Int64) -> String {
        let r = answer(c, ease: ease, crt: crt)
        switch r.card.queue {
        case 1:
            let secs = Double(r.card.due) - Date().timeIntervalSince1970
            return secs < 3600 ? "\(max(1, Int((secs / 60).rounded())))分" : "\(max(1, Int((secs / 3600).rounded())))時間"
        default:
            let d = r.card.ivl
            if d < 30 { return "\(max(1, d))日" }
            if d < 365 { return String(format: "%.1fか月", Double(d) / 30) }
            return String(format: "%.1f年", Double(d) / 365)
        }
    }

    /// 答えを付ける。カードの新しい状態と、記録に書く値を返す
    static func answer(_ card: CardInfo, ease: Ease, crt: Int64, now: Date = Date()) -> Result {
        var c = card
        let nowSecs = Int64(now.timeIntervalSince1970)
        let today = today(crt: crt, now: now)
        let lastIvl = c.ivl
        c.reps += 1
        if c.factor == 0 { c.factor = 2500 }

        func schedule(step: Int, steps: [Int]) {
            // 学習中: steps[step] 分後に。left は「残りステップ数」
            let minutes = steps[min(step, steps.count - 1)]
            c.queue = 1
            c.due = nowSecs + Int64(minutes * 60)
            c.left = steps.count - step
        }
        func graduate(ivl: Int) {
            c.type = 2; c.queue = 2
            c.ivl = min(maxInterval, max(1, ivl))
            c.due = today + Int64(c.ivl)
            c.left = 0
        }

        switch c.type {
        case 0, 1:   // 新規・学習中
            if c.type == 0 { c.type = 1 }
            let steps = learningSteps
            let remaining = c.left > 0 ? c.left : steps.count
            let step = steps.count - remaining      // いまのステップ
            switch ease {
            case .again: schedule(step: 0, steps: steps)
            case .hard:  schedule(step: step, steps: steps)
            case .good:
                if step + 1 >= steps.count { graduate(ivl: graduatingInterval) } else { schedule(step: step + 1, steps: steps) }
            case .easy:  graduate(ivl: easyInterval)
            }
            let revIvl = c.queue == 1 ? -Int(c.due - nowSecs) : c.ivl
            return Result(card: c, revlogType: 0, revlogIvl: revIvl, lastIvl: lastIvl)

        case 3:      // 再学習中
            let steps = relearningSteps
            let remaining = c.left > 0 ? c.left : steps.count
            let step = steps.count - remaining
            switch ease {
            case .again: schedule(step: 0, steps: steps)
            case .hard:  schedule(step: step, steps: steps)
            case .good, .easy:
                if ease == .easy || step + 1 >= steps.count { graduate(ivl: max(1, c.ivl)) } else { schedule(step: step + 1, steps: steps) }
            }
            let revIvl = c.queue == 1 ? -Int(c.due - nowSecs) : c.ivl
            return Result(card: c, revlogType: 2, revlogIvl: revIvl, lastIvl: lastIvl)

        default:     // 復習
            let late = max(0, Int(today - c.due))
            switch ease {
            case .again:
                // 忘れた: 間隔は 1 日からやり直し、易しさを下げて再学習へ
                c.lapses += 1
                c.factor = max(minFactor, c.factor - 200)
                c.ivl = 1
                c.type = 3
                schedule(step: 0, steps: relearningSteps)
                return Result(card: c, revlogType: 1, revlogIvl: -Int(c.due - nowSecs), lastIvl: lastIvl)
            case .hard:
                c.factor = max(minFactor, c.factor - 150)
                c.ivl = max(c.ivl + 1, Int(Double(c.ivl) * 1.2 + Double(late) / 4))
            case .good:
                c.ivl = max(c.ivl + 1, Int(Double(c.ivl + late / 2) * Double(c.factor) / 1000))
            case .easy:
                c.factor += 150
                c.ivl = max(c.ivl + 1, Int(Double(c.ivl + late) * Double(c.factor) / 1000 * 1.3))
            }
            c.ivl = min(maxInterval, c.ivl)
            c.queue = 2; c.type = 2
            c.due = today + Int64(c.ivl)
            c.left = 0
            return Result(card: c, revlogType: 1, revlogIvl: c.ivl, lastIvl: lastIvl)
        }
    }
}
