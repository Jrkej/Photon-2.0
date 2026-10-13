import Foundation
import Network
import PhotonKit
import UIKit

/// `NetworkMonitor` from NWPathMonitor (connected, expensive = cellular or hotspot,
/// constrained = Low Data Mode) plus the battery state for "originals while charging".
final class NetworkPathMonitor: NetworkMonitor, @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.photon.network")
    private let lock = NSLock()
    private var status = NetworkStatus(isConnected: true, isExpensive: false, isConstrained: false, isCharging: false)
    private var onChange: (@Sendable () -> Void)?

    var current: NetworkStatus {
        lock.lock()
        defer { lock.unlock() }
        return status
    }

    /// Starts monitoring; `onChange` runs after every change of path or charging state.
    func start(onChange: @escaping @Sendable () -> Void) {
        lock.lock()
        self.onChange = onChange
        lock.unlock()
        let handler: @Sendable (NWPath) -> Void = { [weak self] path in
            self?.update { s in
                s.isConnected = path.status == .satisfied
                s.isExpensive = path.isExpensive
                s.isConstrained = path.isConstrained
            }
        }
        monitor.pathUpdateHandler = handler
        monitor.start(queue: queue)
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.startBatteryMonitoring() }
        }
    }

    @MainActor
    private func startBatteryMonitoring() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        updateCharging()
        NotificationCenter.default.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil,
                                               queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.updateCharging() }
        }
    }

    @MainActor
    private func updateCharging() {
        let state = UIDevice.current.batteryState
        let charging = state == .charging || state == .full
        update { $0.isCharging = charging }
    }

    private func update(_ body: (inout NetworkStatus) -> Void) {
        lock.lock()
        var next = status
        body(&next)
        let changed = next != status
        status = next
        let handler = onChange
        lock.unlock()
        if changed { handler?() }
    }
}
