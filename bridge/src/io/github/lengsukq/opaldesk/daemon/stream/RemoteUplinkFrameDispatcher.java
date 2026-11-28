package io.github.lengsukq.opaldesk.daemon.stream;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Decodes and routes client-to-host OpalCast binary uplink frames to
 * {@link RemoteStreamServer.SessionListener} callbacks, keeping protocol dispatch isolated from
 * socket lifecycle and session management in {@link RemoteStreamServer}.
 */
final class RemoteUplinkFrameDispatcher {
    private RemoteUplinkFrameDispatcher() {}

    static void dispatch(
            ClientSession session,
            OpalCastProtocol.Frame f,
            RemoteStreamServer.SessionListener listener,
            Consumer<ClientSession> onWifiUpgradeRequest,
            LongSupplier timestampUsSupplier) throws IOException {
        int sid = session.sessionId;
        switch (f.type) {
            case OpalCastProtocol.TYPE_PING: {
                long pingId = OpalCastProtocol.decodePingPong(f.payload);
                session.enqueue(
                        OpalCastProtocol.TYPE_PONG,
                        0,
                        timestampUsSupplier.getAsLong(),
                        OpalCastProtocol.encodePingPong(pingId));
                break;
            }
            case OpalCastProtocol.TYPE_REQUEST_IDR: {
                if (listener != null) listener.onRequestIdr(sid);
                break;
            }
            case OpalCastProtocol.TYPE_RESIZE_CONFIG: {
                OpalCastProtocol.ResizeConfig cfg = OpalCastProtocol.decodeResizeConfig(f.payload);
                if (listener != null) listener.onResizeConfig(sid, cfg);
                break;
            }
            case OpalCastProtocol.TYPE_INPUT_MOUSE: {
                OpalCastProtocol.MouseEventMsg msg = OpalCastProtocol.decodeMouseEvent(f.payload);
                if (listener != null) listener.onMouseEvent(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_INPUT_TOUCH: {
                OpalCastProtocol.TouchEventMsg msg = OpalCastProtocol.decodeTouchEvent(f.payload);
                if (listener != null) listener.onTouchEvent(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_INPUT_KEY: {
                OpalCastProtocol.KeyEventMsg msg = OpalCastProtocol.decodeKeyEvent(f.payload);
                if (listener != null) listener.onKeyEvent(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_INPUT_TEXT: {
                if ((f.flags & OpalCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG) != 0) {
                    OpalCastProtocol.ImageClipboardEventMsg imgMsg =
                            OpalCastProtocol.decodeImageClipboardEvent(f.payload);
                    if (listener != null) listener.onImageClipboardEvent(sid, imgMsg);
                } else {
                    OpalCastProtocol.TextEventMsg msg = OpalCastProtocol.decodeTextEvent(f.payload);
                    if (listener != null) listener.onTextEvent(sid, msg);
                }
                break;
            }
            case OpalCastProtocol.TYPE_SYSTEM_ACTION: {
                OpalCastProtocol.SystemActionMsg msg = OpalCastProtocol.decodeSystemAction(f.payload);
                if (listener != null) listener.onSystemAction(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_FILE_TRANSFER_CHUNK: {
                OpalCastProtocol.FileTransferChunkMsg msg = OpalCastProtocol.decodeFileTransferChunk(f.payload);
                if (listener != null) listener.onFileTransferChunk(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_REQUEST_WIFI_UPGRADE: {
                onWifiUpgradeRequest.accept(session);
                break;
            }
            case OpalCastProtocol.TYPE_LIST_APPS: {
                if (listener != null) listener.onListApps(sid);
                break;
            }
            case OpalCastProtocol.TYPE_WINDOW_ACTION: {
                OpalCastProtocol.WindowActionMsg msg = OpalCastProtocol.decodeWindowAction(f.payload);
                if (listener != null) listener.onWindowAction(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_MIC_CONFIG: {
                OpalCastProtocol.MicConfigMsg msg = OpalCastProtocol.decodeMicConfig(f.payload);
                if (listener != null) listener.onMicConfig(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_MIC_FRAME: {
                if (listener != null && f.payload != null && f.payload.length > 0
                        && f.payload.length <= OpalCastProtocol.MAX_MIC_FRAME_BYTES) {
                    listener.onMicFrame(sid, f.payload, f.timestampUs);
                }
                break;
            }
            case OpalCastProtocol.TYPE_CAMERA_CONFIG: {
                OpalCastProtocol.CameraConfigMsg msg = OpalCastProtocol.decodeCameraConfig(f.payload);
                if (listener != null) listener.onCameraConfig(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_CAMERA_FRAME: {
                if (listener != null && f.payload != null && f.payload.length > 0
                        && f.payload.length <= OpalCastProtocol.MAX_CAMERA_FRAME_BYTES) {
                    listener.onCameraFrame(sid, f.payload, f.timestampUs);
                }
                break;
            }
            case OpalCastProtocol.TYPE_NOTIFICATION_ACTION: {
                session.setCompanionEventsSubscribed(true);
                OpalCastProtocol.NotificationActionMsg msg = OpalCastProtocol.decodeNotificationAction(f.payload);
                if (listener != null) listener.onNotificationAction(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_MEDIA_ACTION: {
                session.setCompanionEventsSubscribed(true);
                OpalCastProtocol.MediaActionMsg msg = OpalCastProtocol.decodeMediaAction(f.payload);
                if (listener != null) listener.onMediaAction(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_FILE_OP_REQUEST: {
                OpalCastProtocol.FileOpRequestMsg msg = OpalCastProtocol.decodeFileOpRequest(f.payload);
                if (listener != null) listener.onFileOpRequest(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_APK_INSTALL_REQUEST: {
                OpalCastProtocol.ApkInstallRequestMsg msg = OpalCastProtocol.decodeApkInstallRequest(f.payload);
                if (listener != null) listener.onApkInstallRequest(sid, msg);
                break;
            }
            case OpalCastProtocol.TYPE_CALL_ACTION: {
                session.setCompanionEventsSubscribed(true);
                OpalCastProtocol.CallActionMsg msg = OpalCastProtocol.decodeCallAction(f.payload);
                if (listener != null) listener.onCallAction(sid, msg);
                break;
            }
            default:
                break;
        }
    }
}
