import PhotonKit
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            switch model.phase {
            case .starting:
                ProgressView()
            case .loggedOut:
                LoginView()
            case .home:
                HomeView()
            case .inSession:
                SessionView()
            }
        }
        .sheet(item: $model.consent) { request in
            ConsentView(request: request)
        }
        .confirmationDialog("You are already in a session", isPresented: Binding(
            get: { model.conflict != nil },
            set: { if !$0 { model.conflict = nil } }
        ), titleVisibility: .visible, presenting: model.conflict) { conflict in
            if conflict.info != nil {
                Button(conflict.retry == nil ? "Leave it" : "Leave it and continue", role: .destructive) {
                    model.leaveConflictAndContinue()
                }
                Button("Rejoin it on this phone") { model.rejoinConflict() }
            }
            Button("Cancel", role: .cancel) { model.conflict = nil }
        } message: { conflict in
            Text(model.describe(conflict))
        }
        .alert(model.alert?.title ?? "", isPresented: Binding(
            get: { model.alert != nil },
            set: { if !$0 { model.alert = nil } }
        ), presenting: model.alert) { _ in
            Button("OK", role: .cancel) {}
        } message: { item in
            Text(item.message)
        }
    }
}

struct LoginView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "camera.aperture")
                .font(.system(size: 64))
                .foregroundStyle(.tint)
            Text("PHOTON")
                .font(.largeTitle.bold())
            Text("Share the photos you take with everyone in a live session, as you take them.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Spacer()
            Button {
                model.login()
            } label: {
                if model.busy {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text("Sign in").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(model.busy)
        }
        .padding(32)
    }
}

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var code = ""
    @State private var showSettings = false

    var body: some View {
        Form {
            Section {
                Button("Start a session") { model.requestCreate() }
                    .disabled(model.busy)
            } footer: {
                Text("You get a join code and an invite link to share.")
            }
            Section {
                TextField("Join code or invite link", text: $code)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .submitLabel(.join)
                    .onSubmit { model.requestJoin(code) }
                Button("Join") { model.requestJoin(code) }
                    .disabled(code.trimmingCharacters(in: .whitespaces).isEmpty || model.busy)
            } header: {
                Text("Join a session")
            } footer: {
                Text("To join with a QR code, scan it with the Camera app.")
            }
        }
        .navigationTitle("PHOTON")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showSettings = true } label: { Image(systemName: "gearshape") }
            }
        }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .overlay { if model.busy { ProgressView() } }
    }
}

/// The consent text, shown before creating or joining a session.
struct ConsentView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let request: ConsentRequest

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Label(Copy.consentTitle, systemImage: "photo.on.rectangle.angled")
                        .font(.title3.bold())
                    Text(Copy.consentBody)
                    Divider()
                    Text(Copy.suspendedTitle).font(.headline)
                    Text(Copy.suspendedBody).foregroundStyle(.secondary)
                }
                .padding()
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    model.confirm(request)
                } label: {
                    Text(confirmTitle).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding()
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.consent = nil; dismiss() }
                }
            }
        }
    }

    private var confirmTitle: String {
        switch request.kind {
        case .create: return "Start and share my photos"
        case .join: return "Join and share my photos"
        case .resume: return "Rejoin and share my photos"
        }
    }
}
