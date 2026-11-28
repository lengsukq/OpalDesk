package io.github.lengsukq.opaldesk.daemon.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * File transfer, remote file browser, storage report, and file pull chunk payload models and
 * codecs delegated by {@link OpalCastProtocol}.
 */
public final class OpalCastFilePayloads {
    private OpalCastFilePayloads() {}

    public static byte[] encodeFileTransferAck(int transferId, int status, String pathOrError) {
        byte[] utf8 = (pathOrError != null ? pathOrError : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(5 + utf8.length).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) (status & 0xFF));
        bb.put(utf8);
        return bb.array();
    }

    public static class FileTransferChunkMsgBase {
        public final int transferId;
        public final int chunkFlags;
        public final String fileName;
        public final long totalSize;
        public final byte[] data;

        protected FileTransferChunkMsgBase(
                int transferId, int chunkFlags, String fileName, long totalSize, byte[] data) {
            this.transferId = transferId;
            this.chunkFlags = chunkFlags & 0xFF;
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.data = (data != null) ? data : OpalCastProtocol.EMPTY_BYTES;
        }
    }

    public static OpalCastProtocol.FileTransferChunkMsg decodeFileTransferChunk(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 5) {
            throw new OpalCastProtocol.ProtocolException("FILE_TRANSFER_CHUNK payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int transferId = bb.getInt();
        int chunkFlags = bb.get() & 0xFF;
        String fileName = null;
        long totalSize = -1L;
        if ((chunkFlags & OpalCastProtocol.FILE_FLAG_START) != 0) {
            if (bb.remaining() < 2) {
                throw new OpalCastProtocol.ProtocolException("FILE_TRANSFER_CHUNK START missing fileNameLen");
            }
            int nameLen = bb.getShort() & 0xFFFF;
            if (bb.remaining() < nameLen + 8) {
                throw new OpalCastProtocol.ProtocolException("FILE_TRANSFER_CHUNK START truncated");
            }
            byte[] nameBytes = new byte[nameLen];
            bb.get(nameBytes);
            fileName = new String(nameBytes, StandardCharsets.UTF_8);
            totalSize = bb.getLong();
        }
        byte[] data = OpalCastProtocol.EMPTY_BYTES;
        if ((chunkFlags & OpalCastProtocol.FILE_FLAG_DATA) != 0 && bb.remaining() > 0) {
            data = new byte[bb.remaining()];
            bb.get(data);
        }
        return new OpalCastProtocol.FileTransferChunkMsg(transferId, chunkFlags, fileName, totalSize, data);
    }

    public static class FileOpRequestMsgBase {
        public final int requestId;
        public final String op;
        public final String path;
        public final String newPath;
        public final int transferId;
        public final int maxDepth;

        protected FileOpRequestMsgBase(
                int requestId, String op, String path, String newPath, int transferId, int maxDepth) {
            this.requestId = requestId;
            this.op = (op != null) ? op.trim() : "";
            this.path = (path != null) ? path : "";
            this.newPath = (newPath != null) ? newPath : "";
            this.transferId = transferId;
            this.maxDepth = maxDepth;
        }
    }

    public static byte[] encodeFileOpRequest(OpalCastProtocol.FileOpRequestMsg msg) {
        OpalCastProtocol.FileOpRequestMsg m = (msg != null)
                ? msg : new OpalCastProtocol.FileOpRequestMsg(0, "list", "", "", 0, 0);
        return new SimpleJson.ObjectBuilder()
                .put("requestId", m.requestId)
                .put("op", m.op)
                .put("path", m.path)
                .put("newPath", m.newPath)
                .put("transferId", m.transferId)
                .put("maxDepth", m.maxDepth)
                .build()
                .getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.FileOpRequestMsg decodeFileOpRequest(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("FILE_OP_REQUEST payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String op = obj.getString("op", "");
            if (op.trim().isEmpty()) {
                throw new OpalCastProtocol.ProtocolException("FILE_OP_REQUEST missing op");
            }
            return new OpalCastProtocol.FileOpRequestMsg(
                    obj.getInt("requestId", 0),
                    op,
                    obj.getString("path", ""),
                    obj.getString("newPath", ""),
                    obj.getInt("transferId", 0),
                    obj.getInt("maxDepth", 0));
        } catch (OpalCastProtocol.ProtocolException pe) {
            throw pe;
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid FILE_OP_REQUEST JSON: " + t.getMessage(), t);
        }
    }

    public static class FileEntryBase {
        public final String name;
        public final String path;
        public final boolean isDir;
        public final long size;
        public final long modifiedMs;
        public final String mime;

        protected FileEntryBase(String name, String path, boolean isDir, long size, long modifiedMs, String mime) {
            this.name = (name != null) ? name : "";
            this.path = (path != null) ? path : "";
            this.isDir = isDir;
            this.size = Math.max(0L, size);
            this.modifiedMs = Math.max(0L, modifiedMs);
            this.mime = (mime != null) ? mime : "";
        }
    }

    public static class StorageCategoryBase {
        public final String name;
        public final long bytes;
        public final int fileCount;

        protected StorageCategoryBase(String name, long bytes, int fileCount) {
            this.name = (name != null) ? name : "";
            this.bytes = Math.max(0L, bytes);
            this.fileCount = Math.max(0, fileCount);
        }
    }

    public static class LargeFileEntryBase {
        public final String name;
        public final String path;
        public final long size;
        public final long modifiedMs;

        protected LargeFileEntryBase(String name, String path, long size, long modifiedMs) {
            this.name = (name != null) ? name : "";
            this.path = (path != null) ? path : "";
            this.size = Math.max(0L, size);
            this.modifiedMs = Math.max(0L, modifiedMs);
        }
    }

    public static class FolderStorageEntryBase {
        public final String path;
        public final String name;
        public final long bytes;
        public final int fileCount;

        protected FolderStorageEntryBase(String path, String name, long bytes, int fileCount) {
            this.path = (path != null) ? path : "";
            this.name = (name != null) ? name : "";
            this.bytes = Math.max(0L, bytes);
            this.fileCount = Math.max(0, fileCount);
        }
    }

    public static class AppStorageEntryBase {
        public final String pkg;
        public final String appLabel;
        public final long apkBytes;
        public final long dataBytes;
        public final long cacheBytes;
        public final long totalBytes;

        protected AppStorageEntryBase(
                String pkg, String appLabel, long apkBytes, long dataBytes, long cacheBytes, long totalBytes) {
            this.pkg = (pkg != null) ? pkg : "";
            this.appLabel = (appLabel != null) ? appLabel : "";
            this.apkBytes = Math.max(0L, apkBytes);
            this.dataBytes = Math.max(0L, dataBytes);
            this.cacheBytes = Math.max(0L, cacheBytes);
            this.totalBytes = Math.max(0L, totalBytes);
        }
    }

    public static class StorageReportBase {
        public final long totalBytes;
        public final long freeBytes;
        public final long usedBytes;
        public final List<OpalCastProtocol.StorageCategory> categories;
        public final List<OpalCastProtocol.LargeFileEntry> largeFiles;
        public final List<OpalCastProtocol.FolderStorageEntry> folders;
        public final List<OpalCastProtocol.AppStorageEntry> apps;

        protected StorageReportBase(
                long totalBytes, long freeBytes, long usedBytes,
                List<OpalCastProtocol.StorageCategory> categories,
                List<OpalCastProtocol.LargeFileEntry> largeFiles,
                List<OpalCastProtocol.FolderStorageEntry> folders,
                List<OpalCastProtocol.AppStorageEntry> apps) {
            this.totalBytes = Math.max(0L, totalBytes);
            this.freeBytes = Math.max(0L, freeBytes);
            this.usedBytes = Math.max(0L, usedBytes);
            this.categories = (categories != null)
                    ? Collections.unmodifiableList(new ArrayList<>(categories))
                    : Collections.emptyList();
            this.largeFiles = (largeFiles != null)
                    ? Collections.unmodifiableList(new ArrayList<>(largeFiles))
                    : Collections.emptyList();
            this.folders = (folders != null && !folders.isEmpty())
                    ? Collections.unmodifiableList(new ArrayList<>(folders))
                    : Collections.emptyList();
            this.apps = (apps != null && !apps.isEmpty())
                    ? Collections.unmodifiableList(new ArrayList<>(apps))
                    : Collections.emptyList();
        }
    }

    public static class FileOpResponseMsgBase {
        public final int requestId;
        public final String op;
        public final boolean ok;
        public final String error;
        public final String path;
        public final List<OpalCastProtocol.FileEntry> entries;
        public final OpalCastProtocol.StorageReport storage;
        public final long totalSize;
        public final String thumbnailBase64;

        protected FileOpResponseMsgBase(
                int requestId, String op, boolean ok, String error, String path,
                List<OpalCastProtocol.FileEntry> entries, OpalCastProtocol.StorageReport storage,
                long totalSize, String thumbnailBase64) {
            this.requestId = requestId;
            this.op = (op != null) ? op : "";
            this.ok = ok;
            this.error = (error != null) ? error : "";
            this.path = (path != null) ? path : "";
            this.entries = (entries != null)
                    ? Collections.unmodifiableList(new ArrayList<>(entries))
                    : Collections.emptyList();
            this.storage = storage;
            this.totalSize = Math.max(0L, totalSize);
            this.thumbnailBase64 = (thumbnailBase64 != null) ? thumbnailBase64 : "";
        }
    }

    public static byte[] encodeFileOpResponse(OpalCastProtocol.FileOpResponseMsg msg) {
        OpalCastProtocol.FileOpResponseMsg m = (msg != null)
                ? msg
                : new OpalCastProtocol.FileOpResponseMsg(0, "", false, "empty", "", Collections.emptyList(), null, 0L, "");
        SimpleJson.ArrayBuilder entriesArr = new SimpleJson.ArrayBuilder();
        for (OpalCastProtocol.FileEntry e : m.entries) {
            if (e == null) continue;
            entriesArr.addRaw(new SimpleJson.ObjectBuilder()
                    .put("name", e.name)
                    .put("path", e.path)
                    .put("isDir", e.isDir)
                    .put("size", e.size)
                    .put("modifiedMs", e.modifiedMs)
                    .put("mime", e.mime)
                    .build());
        }
        SimpleJson.ObjectBuilder root = new SimpleJson.ObjectBuilder()
                .put("requestId", m.requestId)
                .put("op", m.op)
                .put("ok", m.ok)
                .put("error", m.error)
                .put("path", m.path)
                .putRaw("entries", entriesArr.build())
                .put("totalSize", m.totalSize)
                .put("thumbnailBase64", m.thumbnailBase64);
        if (m.storage != null) {
            SimpleJson.ArrayBuilder catArr = new SimpleJson.ArrayBuilder();
            for (OpalCastProtocol.StorageCategory c : m.storage.categories) {
                if (c == null) continue;
                catArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("name", c.name)
                        .put("bytes", c.bytes)
                        .put("fileCount", c.fileCount)
                        .build());
            }
            SimpleJson.ArrayBuilder largeArr = new SimpleJson.ArrayBuilder();
            for (OpalCastProtocol.LargeFileEntry lf : m.storage.largeFiles) {
                if (lf == null) continue;
                largeArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("name", lf.name)
                        .put("path", lf.path)
                        .put("size", lf.size)
                        .put("modifiedMs", lf.modifiedMs)
                        .build());
            }
            SimpleJson.ArrayBuilder foldersArr = new SimpleJson.ArrayBuilder();
            for (OpalCastProtocol.FolderStorageEntry fe : m.storage.folders) {
                if (fe == null) continue;
                foldersArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("path", fe.path)
                        .put("name", fe.name)
                        .put("bytes", fe.bytes)
                        .put("fileCount", fe.fileCount)
                        .build());
            }
            SimpleJson.ArrayBuilder appsArr = new SimpleJson.ArrayBuilder();
            for (OpalCastProtocol.AppStorageEntry ae : m.storage.apps) {
                if (ae == null) continue;
                appsArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("pkg", ae.pkg)
                        .put("appLabel", ae.appLabel)
                        .put("apkBytes", ae.apkBytes)
                        .put("dataBytes", ae.dataBytes)
                        .put("cacheBytes", ae.cacheBytes)
                        .put("totalBytes", ae.totalBytes)
                        .build());
            }
            root.putRaw("storage", new SimpleJson.ObjectBuilder()
                    .put("totalBytes", m.storage.totalBytes)
                    .put("freeBytes", m.storage.freeBytes)
                    .put("usedBytes", m.storage.usedBytes)
                    .putRaw("categories", catArr.build())
                    .putRaw("largeFiles", largeArr.build())
                    .putRaw("folders", foldersArr.build())
                    .putRaw("apps", appsArr.build())
                    .build());
        }
        return root.build().getBytes(StandardCharsets.UTF_8);
    }

    public static OpalCastProtocol.FileOpResponseMsg decodeFileOpResponse(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new OpalCastProtocol.ProtocolException("FILE_OP_RESPONSE payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            List<SimpleJson.JsonObjectView> rawEntries = obj.getObjectList("entries");
            List<OpalCastProtocol.FileEntry> entries = new ArrayList<>(rawEntries.size());
            for (SimpleJson.JsonObjectView e : rawEntries) {
                entries.add(new OpalCastProtocol.FileEntry(
                        e.getString("name", ""),
                        e.getString("path", ""),
                        e.getBoolean("isDir", false),
                        e.getLong("size", 0L),
                        e.getLong("modifiedMs", 0L),
                        e.getString("mime", "")));
            }
            OpalCastProtocol.StorageReport storage = null;
            SimpleJson.JsonObjectView stObj = obj.getObject("storage");
            if (stObj != null) {
                List<SimpleJson.JsonObjectView> rawCats = stObj.getObjectList("categories");
                List<OpalCastProtocol.StorageCategory> cats = new ArrayList<>(rawCats.size());
                for (SimpleJson.JsonObjectView c : rawCats) {
                    cats.add(new OpalCastProtocol.StorageCategory(
                            c.getString("name", ""),
                            c.getLong("bytes", 0L),
                            c.getInt("fileCount", 0)));
                }
                List<SimpleJson.JsonObjectView> rawLarge = stObj.getObjectList("largeFiles");
                List<OpalCastProtocol.LargeFileEntry> largeFiles = new ArrayList<>(rawLarge.size());
                for (SimpleJson.JsonObjectView lf : rawLarge) {
                    largeFiles.add(new OpalCastProtocol.LargeFileEntry(
                            lf.getString("name", ""),
                            lf.getString("path", ""),
                            lf.getLong("size", 0L),
                            lf.getLong("modifiedMs", 0L)));
                }
                List<SimpleJson.JsonObjectView> rawFolders = stObj.getObjectList("folders");
                List<OpalCastProtocol.FolderStorageEntry> folders = new ArrayList<>(rawFolders.size());
                for (SimpleJson.JsonObjectView fe : rawFolders) {
                    folders.add(new OpalCastProtocol.FolderStorageEntry(
                            fe.getString("path", ""),
                            fe.getString("name", ""),
                            fe.getLong("bytes", 0L),
                            fe.getInt("fileCount", 0)));
                }
                List<SimpleJson.JsonObjectView> rawApps = stObj.getObjectList("apps");
                List<OpalCastProtocol.AppStorageEntry> apps = new ArrayList<>(rawApps.size());
                for (SimpleJson.JsonObjectView ae : rawApps) {
                    apps.add(new OpalCastProtocol.AppStorageEntry(
                            ae.getString("pkg", ""),
                            ae.getString("appLabel", ""),
                            ae.getLong("apkBytes", 0L),
                            ae.getLong("dataBytes", 0L),
                            ae.getLong("cacheBytes", 0L),
                            ae.getLong("totalBytes", 0L)));
                }
                storage = new OpalCastProtocol.StorageReport(
                        stObj.getLong("totalBytes", 0L),
                        stObj.getLong("freeBytes", 0L),
                        stObj.getLong("usedBytes", 0L),
                        cats, largeFiles, folders, apps);
            }
            return new OpalCastProtocol.FileOpResponseMsg(
                    obj.getInt("requestId", 0),
                    obj.getString("op", ""),
                    obj.getBoolean("ok", false),
                    obj.getString("error", ""),
                    obj.getString("path", ""),
                    entries, storage,
                    obj.getLong("totalSize", 0L),
                    obj.getString("thumbnailBase64", ""));
        } catch (RuntimeException t) {
            throw new OpalCastProtocol.ProtocolException("Invalid FILE_OP_RESPONSE JSON: " + t.getMessage(), t);
        }
    }

    public static class FilePullChunkMsgBase {
        public final int transferId;
        public final int chunkFlags;
        public final long totalSize;
        public final String nameOrPath;
        public final byte[] data;

        protected FilePullChunkMsgBase(
                int transferId, int chunkFlags, long totalSize, String nameOrPath, byte[] data) {
            this.transferId = transferId;
            this.chunkFlags = chunkFlags & 0xFF;
            this.totalSize = Math.max(0L, totalSize);
            this.nameOrPath = (nameOrPath != null) ? nameOrPath : "";
            this.data = (data != null) ? data : OpalCastProtocol.EMPTY_BYTES;
        }
    }

    public static byte[] encodeFilePullChunk(
            int transferId, int chunkFlags, long totalSize, String nameOrPath,
            byte[] data, int offset, int length) throws OpalCastProtocol.ProtocolException {
        byte[] nameBytes = (nameOrPath != null && !nameOrPath.isEmpty())
                ? nameOrPath.getBytes(StandardCharsets.UTF_8) : OpalCastProtocol.EMPTY_BYTES;
        if (nameBytes.length > 0xFFFF) {
            throw new OpalCastProtocol.ProtocolException("FILE_PULL_CHUNK nameOrPath exceeds uint16 length: " + nameBytes.length);
        }
        int safeLen = Math.max(0, length);
        if (safeLen > 0 && (data == null || offset < 0 || offset + safeLen > data.length)) {
            throw new OpalCastProtocol.ProtocolException("FILE_PULL_CHUNK data bounds out of range");
        }
        int totalPayloadLen = 15 + nameBytes.length + safeLen;
        if (totalPayloadLen > OpalCastProtocol.MAX_PAYLOAD_LEN) {
            throw new OpalCastProtocol.ProtocolException("FILE_PULL_CHUNK exceeds MAX_PAYLOAD_LEN: " + totalPayloadLen);
        }
        ByteBuffer bb = ByteBuffer.allocate(totalPayloadLen).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) (chunkFlags & 0xFF));
        bb.putLong(Math.max(0L, totalSize));
        bb.putShort((short) (nameBytes.length & 0xFFFF));
        if (nameBytes.length > 0) bb.put(nameBytes);
        if (safeLen > 0) bb.put(data, offset, safeLen);
        return bb.array();
    }

    public static byte[] encodeFilePullChunk(OpalCastProtocol.FilePullChunkMsg msg)
            throws OpalCastProtocol.ProtocolException {
        if (msg == null) {
            throw new OpalCastProtocol.ProtocolException("FILE_PULL_CHUNK msg is null");
        }
        byte[] d = (msg.data != null) ? msg.data : OpalCastProtocol.EMPTY_BYTES;
        return encodeFilePullChunk(msg.transferId, msg.chunkFlags, msg.totalSize, msg.nameOrPath, d, 0, d.length);
    }

    public static OpalCastProtocol.FilePullChunkMsg decodeFilePullChunk(byte[] payload)
            throws OpalCastProtocol.ProtocolException {
        if (payload == null || payload.length < 15) {
            throw new OpalCastProtocol.ProtocolException("FILE_PULL_CHUNK payload must be at least 15 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int transferId = bb.getInt();
        int chunkFlags = bb.get() & 0xFF;
        long totalSize = bb.getLong();
        int nameLen = bb.getShort() & 0xFFFF;
        if (bb.remaining() < nameLen) {
            throw new OpalCastProtocol.ProtocolException("FILE_PULL_CHUNK truncated nameOrPath: need " + nameLen);
        }
        String nameOrPath = "";
        if (nameLen > 0) {
            byte[] nameBytes = new byte[nameLen];
            bb.get(nameBytes);
            nameOrPath = new String(nameBytes, StandardCharsets.UTF_8);
        }
        byte[] data = OpalCastProtocol.EMPTY_BYTES;
        if (bb.remaining() > 0) {
            data = new byte[bb.remaining()];
            bb.get(data);
        }
        return new OpalCastProtocol.FilePullChunkMsg(transferId, chunkFlags, totalSize, nameOrPath, data);
    }
}
