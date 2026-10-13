import Foundation

/// User-facing text the design asks for, in one place for review (privacy controls).
enum Copy {
    /// Shown before creating or joining a session.
    static let consentTitle = "Photos you take will be shared"
    static let consentBody = """
    While you are in this session, every photo you take is shared automatically with all of its members, until you pause sharing or leave. Photos taken before you joined and screenshots are never shared.

    Location is removed from your photos before they are uploaded (you can change this in Settings). Members can save the photos they receive to their own library. The server keeps each photo for 30 minutes.
    """

    /// The design's iOS rule: no promise while suspended. Shown on the session screen.
    static let suspendedTitle = "Keep PHOTON open to sync"
    static let suspendedBody = """
    PHOTON syncs only while it is open. When iOS suspends the app in the background, nothing is detected, uploaded or received. Photos you take meanwhile are shared the next time you open PHOTON. An upload already under way may finish in the background, and a notification may wake the app briefly, but neither is guaranteed.
    """

    /// Recall dialog: plain words about what recall can and cannot do.
    static let recallTitle = "Recall this photo?"
    static let recallBody = """
    PHOTON stops sharing it right away: the server refuses every further download, and members' apps delete the thumbnail and preview they keep, as soon as they are online. Recall cannot delete copies that members have already saved to their photo library, and it cannot reach someone whose app never connects again.
    """
    static let recallAction = "Recall for everyone"

    static let limitedAccessTitle = "PHOTON can't see new photos"
    static let limitedAccessBody = """
    With limited photo access, iOS hides the photos you take from PHOTON, so nothing can be shared. Allow access to all photos in Settings to share your photos in this session.
    """

    static let pausedBody = "Sharing is paused. New photos are not uploaded until you resume; resuming shares the photos you took meanwhile."

    static func sessionGone(_ reason: String) -> (String, String) {
        switch reason {
        case "ended": return ("Session ended", "This session is over. Photos already saved to your library stay there.")
        case "removed": return ("Removed from the session", "You are no longer a member of this session.")
        default: return ("Left the session", "You left the session. Photos already saved to your library stay there.")
        }
    }
}
