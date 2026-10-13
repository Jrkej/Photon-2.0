import PhotonKit
import SwiftUI

@main
struct PhotonApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = AppModel(env: .shared)

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                // Universal Links (invite links, the OAuth redirect) arrive here too.
                .onOpenURL { model.open($0) }
        }
        .onChange(of: scenePhase) { _, phase in
            model.scenePhaseChanged(phase)
        }
    }
}
