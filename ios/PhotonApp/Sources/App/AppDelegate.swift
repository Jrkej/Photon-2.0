import BackgroundTasks
import PhotonKit
import UIKit

/// Launch, push, background URLSession and background refresh. Every background path runs
/// the same short catch-up; none of them is relied on (the design: no promise while
/// suspended).
final class AppDelegate: NSObject, UIApplicationDelegate {
    nonisolated static let refreshTaskID = "com.photon.app.catchup"

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Creating the environment re-attaches the background URLSession, so transfer
        // events from before a relaunch are delivered.
        _ = AppEnvironment.shared
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTaskID, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            Self.handleRefresh(task)
        }
        application.registerForRemoteNotifications()
        return true
    }

    // MARK: Push

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let client = AppEnvironment.shared.client
        Task { await client.setPushToken(deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        PhotonLog("push").error("APNs registration failed: \(error.localizedDescription)")
    }

    /// Silent push: the server sends an APNs background push (push type background,
    /// priority 5) with `{"aps": {"content-available": 1}, "sid": ..., "kind": "events"}`
    /// to the token registered through `PUT /v1/devices/current`. It is only a hint that
    /// events are waiting: catch up within the ~30 seconds iOS allows. Dropped, delayed or
    /// merged pushes are covered by the catch-up at the next launch.
    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        let client = AppEnvironment.shared.client
        let newData = await Self.bounded(seconds: 25) { await client.backgroundCatchUp() }
        return newData ? .newData : .noData
    }

    // MARK: Background URLSession

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == BackgroundChunkUploader.identifier else {
            completionHandler()
            return
        }
        _ = AppEnvironment.shared
        BackgroundChunkUploader.shared.backgroundCompletionHandler = completionHandler
    }

    // MARK: Background refresh

    nonisolated static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTaskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    nonisolated private static func handleRefresh(_ task: BGAppRefreshTask) {
        scheduleRefresh()
        let work = Task { @MainActor in
            let client = AppEnvironment.shared.client
            _ = await client.backgroundCatchUp()
        }
        task.expirationHandler = { work.cancel() }
        Task {
            await work.value
            task.setTaskCompleted(success: !work.isCancelled)
        }
    }

    /// Runs `body`, cancelling it after `seconds`.
    nonisolated static func bounded(seconds: UInt64, _ body: @escaping @Sendable () async -> Bool) async -> Bool {
        let work = Task { await body() }
        let timer = Task {
            try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            work.cancel()
        }
        let result = await work.value
        timer.cancel()
        return result
    }
}
