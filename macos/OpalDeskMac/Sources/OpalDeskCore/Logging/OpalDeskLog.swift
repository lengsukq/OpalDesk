import os

/// Centralized `os.Logger` categories for OpalDeskCore and OpalDeskMac.
/// Also provides structured recording helpers into `OpalLogStore.shared` for in-app diagnostics.
public enum OpalDeskLog {
    private static let subsystem = "io.github.lengsukq.opaldesk.mac"

    public static let session = Logger(subsystem: subsystem, category: "session")
    public static let video = Logger(subsystem: subsystem, category: "video")
    public static let audio = Logger(subsystem: subsystem, category: "audio")
    public static let adb = Logger(subsystem: subsystem, category: "adb")
    public static let trust = Logger(subsystem: subsystem, category: "trust")
    public static let clipboard = Logger(subsystem: subsystem, category: "clipboard")
    public static let file = Logger(subsystem: subsystem, category: "file")
    public static let popout = Logger(subsystem: subsystem, category: "popout")
    public static let feedback = Logger(subsystem: subsystem, category: "feedback")
    public static let app = Logger(subsystem: subsystem, category: "app")

    public static func info(_ tag: String, _ message: String) {
        logger(for: tag).info("\(message, privacy: .public)")
        OpalLogStore.shared.append(level: .info, tag: tag, message: message)
    }

    public static func warn(_ tag: String, _ message: String) {
        logger(for: tag).warning("\(message, privacy: .public)")
        OpalLogStore.shared.append(level: .warn, tag: tag, message: message)
    }

    public static func error(_ tag: String, _ message: String) {
        logger(for: tag).error("\(message, privacy: .public)")
        OpalLogStore.shared.append(level: .error, tag: tag, message: message)
    }

    private static func logger(for tag: String) -> Logger {
        switch tag.lowercased() {
        case "session": return session
        case "video": return video
        case "audio": return audio
        case "adb": return adb
        case "trust": return trust
        case "clipboard": return clipboard
        case "file": return file
        case "popout": return popout
        case "feedback", "sentry": return feedback
        default: return app
        }
    }
}
