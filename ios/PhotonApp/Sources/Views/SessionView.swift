import Photos
import PhotonKit
import SwiftUI

struct SessionView: View {
    @Environment(AppModel.self) private var model
    @State private var showSettings = false
    @State private var confirmLeave = false
    @State private var confirmEnd = false

    var body: some View {
        if let session = model.session {
            List {
                SyncStatusSection(session: session)
                if model.libraryStatus != .authorized {
                    LibraryAccessSection(status: model.libraryStatus)
                }
                InviteSection(session: session)
                ReceivedSection()
                MyPhotosSection()
                MembersSection(session: session)
                Section {
                    Button("Leave session", role: .destructive) { confirmLeave = true }
                    if session.isHost {
                        Button("End session for everyone", role: .destructive) { confirmEnd = true }
                    }
                }
            }
            .navigationTitle("Session")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .refreshable { await model.env.client.didBecomeActive(); model.reload() }
            .confirmationDialog("Leave this session?", isPresented: $confirmLeave, titleVisibility: .visible) {
                Button("Leave", role: .destructive) { model.leave() }
            } message: {
                Text("Your new photos stop being shared. Photos already saved to your library stay there.")
            }
            .confirmationDialog("End the session for everyone?", isPresented: $confirmEnd, titleVisibility: .visible) {
                Button("End session", role: .destructive) { model.endSession() }
            } message: {
                Text("Nobody can join or share anymore, and the server deletes every photo of this session.")
            }
        }
    }
}

/// The visible sync indicator, the pause switch and the statement that nothing syncs
/// while the app is suspended.
struct SyncStatusSection: View {
    @Environment(AppModel.self) private var model
    let session: SessionState

    var body: some View {
        Section {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundStyle(color)
                    .symbolEffect(.pulse, isActive: isSyncing)
                VStack(alignment: .leading) {
                    Text(title).font(.headline)
                    Text(detail).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Toggle("Share my new photos", isOn: Binding(
                get: { !session.paused },
                set: { model.setPaused(!$0) }
            ))
            if session.paused {
                Text(Copy.pausedBody).font(.footnote).foregroundStyle(.secondary)
            }
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(Copy.suspendedTitle).bold()
                Text(Copy.suspendedBody)
            }
        }
    }

    private var isSyncing: Bool { !session.paused && model.connection == .live }

    private var title: String {
        if session.paused { return "Sharing paused" }
        switch model.connection {
        case .live: return "Syncing"
        case .connecting: return "Connecting..."
        case .waiting: return "Reconnecting..."
        case .idle: return "Not connected"
        }
    }

    private var detail: String {
        let pending = model.uploads.filter { !$0.state.isFinal }.count
        return pending == 0 ? "All your photos are shared" : "\(pending) photo\(pending == 1 ? "" : "s") waiting to upload"
    }

    private var icon: String {
        if session.paused { return "pause.circle.fill" }
        return model.connection == .live ? "dot.radiowaves.left.and.right" : "wifi.exclamationmark"
    }

    private var color: Color {
        if session.paused { return .orange }
        return model.connection == .live ? .green : .secondary
    }
}

struct LibraryAccessSection: View {
    let status: PHAuthorizationStatus
    @Environment(AppModel.self) private var model

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Label(Copy.limitedAccessTitle, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                    .font(.headline)
                Text(Copy.limitedAccessBody).font(.footnote)
            }
            if status == .notDetermined {
                Button("Allow photo access") { Task { await model.ensureLibraryAccess(); model.reload() } }
            } else {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            }
        }
    }
}

struct InviteSection: View {
    @Environment(AppModel.self) private var model
    let session: SessionState

    var body: some View {
        Section("Invite") {
            if !session.qrPayload.isEmpty {
                QRCodeView(text: session.qrPayload)
                    .frame(width: 180, height: 180)
                    .frame(maxWidth: .infinity)
            }
            if !session.joinCode.isEmpty {
                LabeledContent("Join code") {
                    Text(session.joinCode).font(.title3.monospaced()).textSelection(.enabled)
                }
            }
            if let url = URL(string: session.inviteURL), !session.inviteURL.isEmpty {
                ShareLink("Share invite link", item: url)
            }
            if session.isHost {
                Button("New code and link") { model.rotateInvites() }
            }
        }
    }
}

struct ReceivedSection: View {
    @Environment(AppModel.self) private var model
    private let columns = [GridItem(.adaptive(minimum: 96), spacing: 4)]

    var body: some View {
        Section("Shared with you") {
            if model.received.isEmpty {
                Text("Photos other members take appear here.").foregroundStyle(.secondary)
            } else {
                LazyVGrid(columns: columns, spacing: 4) {
                    ForEach(model.received, id: \.sha256Hex) { photo in
                        NavigationLink {
                            ReceivedPhotoView(photo: photo)
                        } label: {
                            ReceivedThumbnail(photo: photo)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }
}

struct MyPhotosSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Section("Your photos") {
            if model.uploads.isEmpty {
                Text("Photos you take while in this session are shared automatically.").foregroundStyle(.secondary)
            }
            ForEach(model.uploads, id: \.id) { job in
                NavigationLink {
                    MyPhotoView(job: job)
                } label: {
                    HStack {
                        AssetThumbnail(localID: job.id)
                            .frame(width: 48, height: 48)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                        VStack(alignment: .leading) {
                            Text(Date(timeIntervalSince1970: Double(job.takenAtMs) / 1000), style: .time)
                            Text(UploadStateText.label(job)).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }
}

enum UploadStateText {
    static func label(_ job: UploadJob) -> String {
        switch job.state {
        case .pending, .hashed: return "Waiting to upload"
        case .uploading: return job.nextAttemptAtMs == nil ? "Uploading" : "Will retry"
        case .waiting: return "Someone else is uploading the same photo"
        case .synced: return "Shared"
        case .skipped: return "Not shared"
        case .failed: return "Refused by the server"
        }
    }
}

struct MembersSection: View {
    @Environment(AppModel.self) private var model
    let session: SessionState

    var body: some View {
        Section("Members (\(session.memberIDs.count))") {
            ForEach(session.memberIDs, id: \.self) { member in
                HStack {
                    Text(member == session.userID ? "You" : member)
                    if member == session.hostID {
                        Spacer()
                        Text("Host").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    if session.isHost && member != session.userID {
                        Button("Remove", role: .destructive) { model.kick(member) }
                    }
                }
            }
        }
    }
}
