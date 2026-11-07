import SwiftUI

@main
struct AnkiPreviewApp: App {
    @StateObject private var store = Store()
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                // 既定では端末のテーマに従う。設定で固定もできる
                .preferredColorScheme(store.appearance == "light" ? .light : store.appearance == "dark" ? .dark : nil)
        }
    }
}
