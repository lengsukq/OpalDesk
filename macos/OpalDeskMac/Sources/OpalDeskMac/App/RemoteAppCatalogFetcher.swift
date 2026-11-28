import Foundation
import OpalDeskCore

public struct RemoteAppCatalogResult: Sendable {
    public let apps: [RemoteAppInfo]
    public let deviceName: String
    public let verifiedFingerprint: String
    public let authToken: String
    public let isPermanentTrust: Bool
}

/// Lightweight one-shot `app_catalog` OpalCast client that fetches installed Android apps
/// without spinning up a remote VirtualDisplay or hardware video encoder.
@MainActor
public final class RemoteAppCatalogFetcher {
    @MainActor
    private final class RequestState {
        var resumed = false
        var resolvedDeviceName = ""
        var resolvedToken: String
        var resolvedFingerprint = ""
        var resolvedPermanent = false

        init(initialToken: String) {
            self.resolvedToken = initialToken
        }
    }

    private var activeSession: OpalCastSession?
    private var timeoutTask: Task<Void, Never>?

    public init() {}

    public func cancel() {
        timeoutTask?.cancel()
        timeoutTask = nil
        activeSession?.disconnect()
        activeSession = nil
    }

    public func fetchApps(
        endpoint: OpalCastEndpoint,
        transportHint: OpalCastTransportHint,
        clientId: String,
        authToken: String,
        trustStore: TrustStore,
        onStatus: @escaping @MainActor (String) -> Void
    ) async throws -> RemoteAppCatalogResult {
        cancel()

        return try await withCheckedThrowingContinuation { continuation in
            let session = OpalCastSession(trustStore: trustStore)
            let req = RequestState(initialToken: authToken)
            self.activeSession = session

            let finish: @MainActor (Result<RemoteAppCatalogResult, Error>) -> Void = { [weak self] result in
                guard !req.resumed else { return }
                req.resumed = true
                self?.timeoutTask?.cancel()
                self?.timeoutTask = nil
                session.disconnect()
                if self?.activeSession === session {
                    self?.activeSession = nil
                }
                continuation.resume(with: result)
            }

            self.timeoutTask = Task { @MainActor in
                guard await sleepCancellable(nanoseconds: 70_000_000_000) else { return }
                finish(.failure(NSError(
                    domain: "OpalDeskCatalog",
                    code: -2,
                    userInfo: [NSLocalizedDescriptionKey: OpalL10n.text("app.catalog.timeout")]
                )))
            }

            onStatus(OpalL10n.text("app.catalog.connecting"))

            session.connect(to: endpoint) { event in
                Task { @MainActor in
                    guard !req.resumed else { return }
                    switch event {
                    case .verifiedPeerFingerprint(let fp):
                        req.resolvedFingerprint = fp
                    case .stateChanged(let state):
                        switch state {
                        case .connected:
                            if transportHint == .wifiTls && authToken.isEmpty {
                                onStatus(OpalL10n.text("app.catalog.waiting_approval"))
                            } else {
                                onStatus(OpalL10n.text("app.catalog.requesting"))
                            }
                            do {
                                let hello = HelloPayload(
                                    clientName: StreamSessionBridge.localClientName,
                                    clientId: clientId,
                                    width: 720,
                                    height: 1280,
                                    dpi: 240,
                                    fps: 60,
                                    bitrateKbps: 4000,
                                    displayMode: Int(OpalCastDisplayMode.dex.rawValue),
                                    audioEnabled: false,
                                    authToken: authToken,
                                    transportHint: transportHint.rawValue,
                                    sessionRole: OpalCastSessionRole.appCatalog.rawValue
                                )
                                session.send(try OpalCastFrame.hello(hello))
                            } catch {
                                finish(.failure(error))
                            }
                        case .failed(let msg):
                            finish(.failure(NSError(
                                domain: "OpalDeskCatalog",
                                code: -1,
                                userInfo: [NSLocalizedDescriptionKey: msg]
                            )))
                        case .disconnected:
                            finish(.failure(NSError(
                                domain: "OpalDeskCatalog",
                                code: -3,
                                userInfo: [NSLocalizedDescriptionKey: OpalL10n.text("app.catalog.disconnected")]
                            )))
                        default:
                            break
                        }
                    case .frame(let frame):
                        switch frame.type {
                        case .authResult:
                            do {
                                let auth = try AuthResultPayload.decode(from: frame.payload)
                                guard auth.ok else {
                                    let msg = auth.message.isEmpty ? OpalL10n.text("app.catalog.denied") : auth.message
                                    finish(.failure(NSError(
                                        domain: "OpalDeskCatalog",
                                        code: -4,
                                        userInfo: [NSLocalizedDescriptionKey: msg]
                                    )))
                                    return
                                }
                                if !auth.deviceName.isEmpty { req.resolvedDeviceName = auth.deviceName }
                                if !auth.authToken.isEmpty { req.resolvedToken = auth.authToken }
                                req.resolvedPermanent = auth.permanent
                                if req.resolvedFingerprint.isEmpty && !auth.certFingerprint.isEmpty {
                                    req.resolvedFingerprint = auth.certFingerprint
                                }
                                onStatus(OpalL10n.text("app.catalog.reading"))
                                session.send(.listApps())
                            } catch {
                                finish(.failure(error))
                            }
                        case .appList:
                            do {
                                let list = try AppListPayload.decode(from: frame.payload)
                                finish(.success(RemoteAppCatalogResult(
                                    apps: list.apps,
                                    deviceName: req.resolvedDeviceName,
                                    verifiedFingerprint: req.resolvedFingerprint,
                                    authToken: req.resolvedToken,
                                    isPermanentTrust: req.resolvedPermanent
                                )))
                            } catch {
                                finish(.failure(error))
                            }
                        default:
                            break
                        }
                    case .error(let err):
                        if case .certificateFingerprintMismatch = err {
                            finish(.failure(err))
                        }
                    case .rttUpdated:
                        break
                    }
                }
            }
        }
    }
}
