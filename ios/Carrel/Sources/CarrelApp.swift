import SwiftUI

@main
struct CarrelApp: App {
    @StateObject private var store = Store()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                // 開いたとき (前面に戻ったとき) に、Mac 側の更新があれば自動で取り込み直す
                .onChange(of: phase) { _, p in if p == .active { Task { await store.checkForUpdates() } } }
                .task { await store.checkForUpdates() }
                // 既定では端末のテーマに従う。設定で固定もできる
                .preferredColorScheme(store.appearance == "light" ? .light : store.appearance == "dark" ? .dark : nil)
        }
    }
}
