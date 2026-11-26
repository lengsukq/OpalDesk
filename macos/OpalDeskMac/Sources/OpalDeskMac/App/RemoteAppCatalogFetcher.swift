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
                    userInfo: [NSLocalizedDescriptionKey: "获取应用列表超时，请确认手机端已开启共享并允许连接"]
                )))
            }

            onStatus("正在连接手机获取应用列表…")

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
                                onStatus("请在手机弹窗中点击「允许」以获取应用列表…")
                            } else {
                                onStatus("已连接，正在请求应用目录…")
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
                                userInfo: [NSLocalizedDescriptionKey: "获取应用列表前连接已断开"]
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
                                    let msg = auth.message.isEmpty ? "手机端拒绝了应用目录请求" : auth.message
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
                                onStatus("已授权，正在读取已安装应用…")
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
