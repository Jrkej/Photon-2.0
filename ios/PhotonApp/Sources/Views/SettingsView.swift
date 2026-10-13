import PhotonKit
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Remove location before sharing", isOn: Binding(
                        get: { model.settings.stripLocation },
                        set: { model.setStripLocation($0) }
                    ))
                } footer: {
                    Text("On by default. PHOTON removes the GPS position from each photo before it leaves your phone.")
                }
                Section {
                    Picker("Save received photos", selection: Binding(
                        get: { model.settings.originals },
                        set: { model.setOriginals($0) }
                    )) {
                        Text("On any network").tag(OriginalsPolicy.always)
                        Text("On Wi-Fi or while charging").tag(OriginalsPolicy.wifiOrCharging)
                    }
                } footer: {
                    Text("Thumbnails always arrive first. Full photos are saved to the PHOTON album in your library; the server keeps them for 30 minutes.")
                }
                Section {
                    Text(Copy.suspendedBody).font(.footnote).foregroundStyle(.secondary)
                } header: {
                    Text(Copy.suspendedTitle)
                }
                if model.loggedIn {
                    Section {
                        Button("Sign out", role: .destructive) { model.logout(); dismiss() }
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
