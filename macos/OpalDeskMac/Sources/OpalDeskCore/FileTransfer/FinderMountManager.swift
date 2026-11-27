import Foundation
import os

// MARK: - Command Execution Abstraction & State Models

/// Result of executing a mount/unmount system utility (`mount_webdav`, `umount`, `diskutil`).
public struct MountCommandResult: Sendable, Equatable {
    public let exitCode: Int32
    public let stdout: String
    public let stderr: String

    public init(exitCode: Int32, stdout: String = "", stderr: String = "") {
        self.exitCode = exitCode
        self.stdout = stdout
        self.stderr = stderr
    }
}

/// Injectable asynchronous command runner for testing `FinderMountManager` without invoking live OS mounts.
public typealias MountCommandExecutor = @Sendable (
    _ executable: String,
    _ arguments: [String],
    _ timeout: Double
) async throws -> MountCommandResult

/// Lifecycle state of the Finder WebDAV mount for a connected Android device.
public enum FinderMountState: Sendable, Equatable {
    case unmounted
    case mounting
    case mounted(mountPoint: URL, serverURL: URL)
    case failed(String)

    public var isMounted: Bool {
        if case .mounted = self { return true }
        return false
    }

    public var mountPoint: URL? {
        if case let .mounted(url, _) = self { return url }
        return nil
    }
}

public enum FinderMountError: Error, Sendable, Equatable, LocalizedError {
    case mountFailed(String)
    case invalidMountPoint(String)

    public var errorDescription: String? {
        switch self {
        case .mountFailed(let detail):
            return "挂载到访达失败：\(detail)"
        case .invalidMountPoint(let detail):
            return "挂载目录无效：\(detail)"
        }
    }
}

// MARK: - FinderMountManager

/// Manages mounting an Android device's filesystem into macOS Finder via a loopback `LocalWebDavServer`
/// and `/sbin/mount_webdav` at `~/Library/Application Support/OpalDesk/Mounts/<SanitizedDeviceName>`.
public final class FinderMountManager: Sendable {
    private static let logger = Logger(subsystem: "io.github.lengsukq.opaldesk.mac", category: "webdav.mount")
    private static let mountTimeoutSeconds: Double = 15.0
    private static let unmountTimeoutSeconds: Double = 10.0
    private static let maxSanitizedNameLength = 64

    private struct MutableState {
        var mountState: FinderMountState = .unmounted
        var activeServer: LocalWebDavServer?
        var activeMountPoint: URL?
        var onStateChanged: (@Sendable (FinderMountState) -> Void)?
    }

    public let mountsRootURL: URL
    private let commandExecutor: MountCommandExecutor
    private let state = OSAllocatedUnfairLock(initialState: MutableState())

    public static func defaultMountsRootURL() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support", isDirectory: true)
        return appSupport
            .appendingPathComponent("OpalDesk", isDirectory: true)
            .appendingPathComponent("Mounts", isDirectory: true)
    }

    /// Sanitizes an arbitrary Android device name into a safe single macOS path component,
    /// stripping control characters, path separators (`/`, `\`, `:`), and leading dots.
    public static func sanitizeDeviceName(_ raw: String) -> String {
        let filteredScalars = raw.unicodeScalars.compactMap { scalar -> UnicodeScalar? in
            if CharacterSet.controlCharacters.contains(scalar) || scalar.value == 0 {
                return nil
            }
            if scalar == "/" || scalar == "\\" || scalar == ":" {
                return "_"
            }
            return scalar
        }
        var cleaned = String(String.UnicodeScalarView(filteredScalars))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        while cleaned.hasPrefix(".") {
            cleaned.removeFirst()
        }
        cleaned = cleaned
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if cleaned.count > maxSanitizedNameLength {
            cleaned = String(cleaned.prefix(maxSanitizedNameLength)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if cleaned.isEmpty || cleaned == "." || cleaned == ".." {
            return "AndroidDevice"
        }
        return cleaned
    }

    public init(
        mountsRootURL: URL = FinderMountManager.defaultMountsRootURL(),
        commandExecutor: MountCommandExecutor? = nil,
        onStateChanged: (@Sendable (FinderMountState) -> Void)? = nil
    ) {
        self.mountsRootURL = mountsRootURL
        self.commandExecutor = commandExecutor ?? { executable, arguments, timeout in
            let result = try await ProcessRunner.run(executable: executable, arguments: arguments, timeout: timeout)
            return MountCommandResult(exitCode: result.exitCode, stdout: result.stdout, stderr: result.stderr)
        }
        self.state.withLock { $0.onStateChanged = onStateChanged }
        cleanupStaleMountDirectories()
    }

    deinit {
        let server = state.withLock { s -> LocalWebDavServer? in
            let current = s.activeServer
            s.activeServer = nil
            return current
        }
        server?.stop()
    }

    public var currentState: FinderMountState {
        state.withLock { $0.mountState }
    }

    public func setOnStateChanged(_ handler: (@Sendable (FinderMountState) -> Void)?) {
        state.withLock { $0.onStateChanged = handler }
    }

    /// Starts a loopback `LocalWebDavServer` for `backend` and mounts it into Finder under `mountsRootURL`.
    @discardableResult
    public func mount(
        deviceName: String,
        backend: any RemoteFileSystemBackend,
        rootPath: String = "/sdcard"
    ) async throws -> URL {
        // Tear down any prior mount cleanly before establishing a new one.
        if state.withLock({ $0.activeServer != nil || $0.activeMountPoint != nil }) {
            await unmount()
        }

        updateState(.mounting)

        let sanitized = Self.sanitizeDeviceName(deviceName)
        let mountPoint = mountsRootURL.appendingPathComponent(sanitized, isDirectory: true)

        let server = LocalWebDavServer(backend: backend, rootPath: rootPath)
        let serverURL: URL
        do {
            try FileManager.default.createDirectory(at: mountPoint, withIntermediateDirectories: true)
            serverURL = try await server.start()
        } catch {
            server.stop()
            removeMountDirectoryIfEmpty(mountPoint)
            let reason = error.localizedDescription
            updateState(.failed(reason))
            throw FinderMountError.mountFailed(reason)
        }

        do {
            let result = try await commandExecutor(
                "/sbin/mount_webdav",
                ["-S", "-v", sanitized, serverURL.absoluteString, mountPoint.path],
                Self.mountTimeoutSeconds
            )
            guard result.exitCode == 0 else {
                let detail = result.stderr.trimmingCharacters(in: .whitespacesAndNewlines)
                let msg = detail.isEmpty ? "mount_webdav 退出码 \(result.exitCode)" : detail
                server.stop()
                removeMountDirectoryIfEmpty(mountPoint)
                updateState(.failed(msg))
                throw FinderMountError.mountFailed(msg)
            }
        } catch let mountErr as FinderMountError {
            throw mountErr
        } catch {
            server.stop()
            removeMountDirectoryIfEmpty(mountPoint)
            let msg = error.localizedDescription
            updateState(.failed(msg))
            throw FinderMountError.mountFailed(msg)
        }

        let callback: (@Sendable (FinderMountState) -> Void)? = state.withLock { s in
            s.activeServer = server
            s.activeMountPoint = mountPoint
            s.mountState = .mounted(mountPoint: mountPoint, serverURL: serverURL)
            return s.onStateChanged
        }
        Self.logger.info("Mounted Finder WebDAV volume '\(sanitized, privacy: .public)' at \(mountPoint.path, privacy: .public)")
        callback?(.mounted(mountPoint: mountPoint, serverURL: serverURL))
        return mountPoint
    }

    /// Gracefully unmounts the active Finder WebDAV volume (falling back to `diskutil unmount force`),
    /// stops the loopback server, and removes stale empty mount directories.
    public func unmount() async {
        let (server, mountPoint): (LocalWebDavServer?, URL?) = state.withLock { s in
            let currentServer = s.activeServer
            let currentPoint = s.activeMountPoint
            s.activeServer = nil
            s.activeMountPoint = nil
            return (currentServer, currentPoint)
        }

        if let mountPoint {
            var unmounted = false
            do {
                let res = try await commandExecutor("/sbin/umount", [mountPoint.path], Self.unmountTimeoutSeconds)
                unmounted = (res.exitCode == 0)
            } catch {
                Self.logger.warning("umount failed for \(mountPoint.path, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }

            if !unmounted {
                do {
                    let forced = try await commandExecutor(
                        "/usr/sbin/diskutil",
                        ["unmount", "force", mountPoint.path],
                        Self.unmountTimeoutSeconds
                    )
                    if forced.exitCode != 0 {
                        Self.logger.warning("diskutil unmount force returned \(forced.exitCode) for \(mountPoint.path, privacy: .public): \(forced.stderr, privacy: .public)")
                    }
                } catch {
                    Self.logger.warning("diskutil unmount force failed for \(mountPoint.path, privacy: .public): \(error.localizedDescription, privacy: .public)")
                }
            }
        }

        server?.stop()

        if let mountPoint {
            removeMountDirectoryIfEmpty(mountPoint)
        }
        cleanupStaleMountDirectories()
        updateState(.unmounted)
    }

    /// Removes stale empty subdirectories under `mountsRootURL` that are not active mounted volumes.
    public func cleanupStaleMountDirectories() {
        let fm = FileManager.default
        guard fm.fileExists(atPath: mountsRootURL.path),
              let entries = try? fm.contentsOfDirectory(
                  at: mountsRootURL,
                  includingPropertiesForKeys: [.isDirectoryKey, .isVolumeKey],
                  options: []
              )
        else { return }

        let activePath = state.withLock { $0.activeMountPoint?.standardizedFileURL.path }
        for entry in entries {
            if let activePath, entry.standardizedFileURL.path == activePath {
                continue
            }
            removeMountDirectoryIfEmpty(entry)
        }
    }

    // MARK: - Private Helpers

    private func updateState(_ next: FinderMountState) {
        let callback: (@Sendable (FinderMountState) -> Void)? = state.withLock { s in
            s.mountState = next
            return s.onStateChanged
        }
        callback?(next)
    }

    private func removeMountDirectoryIfEmpty(_ url: URL) {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else { return }

        // Never delete a directory that is currently an active volume mount point.
        if let values = try? url.resourceValues(forKeys: [.isVolumeKey]), values.isVolume == true {
            return
        }
        guard let children = try? fm.contentsOfDirectory(atPath: url.path), children.isEmpty else {
            return
        }
        try? fm.removeItem(at: url)
    }
}
