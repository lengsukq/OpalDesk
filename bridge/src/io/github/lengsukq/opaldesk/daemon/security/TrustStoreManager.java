package io.github.lengsukq.opaldesk.daemon.security;

import io.github.lengsukq.opaldesk.daemon.stream.BleNearbyProtocol;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;
import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages trusted wireless peer credentials (32-byte random hex authTokens stored strictly as
 * SHA-256 hashes at {@code /data/local/tmp/opaldesk_trusted_clients.conf}), single-use in-memory
 * handover tokens for one-time catalog->floating-app transitions, and anti-spam rejection backoff.
 *
 * <p>Replaces legacy 6-digit static PIN pairing with explicit host consent (allow once / allow
 * permanently / reject) plus instant per-device revocation.
 */
public final class TrustStoreManager {
    public static final String DEFAULT_STORE_PATH = "/data/local/tmp/opaldesk_trusted_peers.conf";
    public static final int MAX_TRUSTED_CLIENTS = 64;
    public static final int MAX_FAILURES_BEFORE_LOCKOUT = 5;
    public static final long BASE_LOCKOUT_MS = 30_000L;
    public static final long MAX_LOCKOUT_MS = 300_000L;
    public static final long FAILURE_WINDOW_RESET_MS = 10L * 60_000L;
    public static final long EPHEMERAL_HANDOVER_TTL_MS = 90_000L;

    public interface Clock {
        long nowMs();
    }

    public static final class TrustedPeer {
        public final String clientId;
        public final String clientName;
        public final String tokenSha256;
        public final long createdAtMs;

        public TrustedPeer(String clientId, String clientName, String tokenSha256, long createdAtMs) {
            this.clientId = clientId;
            this.clientName = clientName;
            this.tokenSha256 = tokenSha256;
            this.createdAtMs = createdAtMs;
        }
    }

    public static final class TokenVerifyResult {
        public static final int STATUS_VALID_PERMANENT = 1;
        public static final int STATUS_VALID_EPHEMERAL = 2;
        public static final int STATUS_NEEDS_APPROVAL = 0;
        public static final int STATUS_RATE_LIMITED = -1;
        public static final int STATUS_INVALID_CLIENT = -2;

        public final int status;
        public final String message;

        private TokenVerifyResult(int status, String message) {
            this.status = status;
            this.message = (message != null) ? message : "";
        }

        public boolean isAuthenticated() {
            return status == STATUS_VALID_PERMANENT || status == STATUS_VALID_EPHEMERAL;
        }

        public boolean isPermanent() {
            return status == STATUS_VALID_PERMANENT;
        }

        public boolean isRateLimited() {
            return status == STATUS_RATE_LIMITED;
        }

        public static TokenVerifyResult validPermanent() {
            return new TokenVerifyResult(STATUS_VALID_PERMANENT, "Authenticated with permanent trusted token");
        }

        public static TokenVerifyResult validEphemeral() {
            return new TokenVerifyResult(STATUS_VALID_EPHEMERAL, "Authenticated with one-time session transfer token");
        }

        public static TokenVerifyResult needsApproval() {
            return new TokenVerifyResult(STATUS_NEEDS_APPROVAL, "Host approval required");
        }

        public static TokenVerifyResult invalidClient(String msg) {
            return new TokenVerifyResult(STATUS_INVALID_CLIENT, msg);
        }

        public static TokenVerifyResult rateLimited(long retryAfterMs) {
            long sec = Math.max(1L, (retryAfterMs + 999L) / 1000L);
            return new TokenVerifyResult(STATUS_RATE_LIMITED, "请求过于频繁，请 " + sec + " 秒后重试");
        }
    }

    private static final class EphemeralHandover {
        final String clientId;
        final String tokenSha256;
        final long expiresAtMs;

        EphemeralHandover(String clientId, String tokenSha256, long expiresAtMs) {
            this.clientId = clientId;
            this.tokenSha256 = tokenSha256;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static final class FailureBucket {
        int consecutiveFailures;
        long lastFailureMs;
        long lockedUntilMs;
    }

    private final File storeFile;
    private final SecureRandom rng;
    private final Clock clock;
    private final Object lock = new Object();
    private final LinkedHashMap<String, TrustedPeer> peers = new LinkedHashMap<>();
    private final Map<String, EphemeralHandover> ephemeralHandovers = new HashMap<>();
    private final Map<String, FailureBucket> failureBuckets = new HashMap<>();
    private final FailureBucket globalFailures = new FailureBucket();

    public TrustStoreManager() {
        this(new File(DEFAULT_STORE_PATH), new SecureRandom(), System::currentTimeMillis);
    }

    public TrustStoreManager(File storeFile) {
        this(storeFile, new SecureRandom(), System::currentTimeMillis);
    }

    public TrustStoreManager(File storeFile, SecureRandom rng, Clock clock) {
        this.storeFile = storeFile;
        this.rng = (rng != null) ? rng : new SecureRandom();
        this.clock = (clock != null) ? clock : System::currentTimeMillis;
        loadFromDisk();
    }

    /**
     * Checks whether {@code providedToken} grants immediate authentication for {@code rawClientId},
     * or whether the request is currently rate-limited, or needs interactive host approval.
     *
     * <p>A revoked or stale token gracefully falls through to {@link TokenVerifyResult#needsApproval()}
     * instead of permanently blocking the user after revocation.
     */
    public TokenVerifyResult verifyTokenOrCheckRateLimit(
            String rawClientId,
            String rawClientName,
            String providedToken,
            String peerRateLimitKey) {
        String clientId = sanitizeClientId(rawClientId);
        if (clientId.isEmpty()) {
            return TokenVerifyResult.invalidClient("Missing or invalid clientId");
        }
        String clientName = sanitizeClientName(rawClientName, clientId);
        String bucketKey = normalizeBucketKey(peerRateLimitKey);

        synchronized (lock) {
            long now = clock.nowMs();
            pruneExpiredEphemeralsLocked(now);

            if (providedToken != null && !providedToken.isEmpty()) {
                String providedHash = CryptoHex.sha256Hex(providedToken);
                // 1. Check permanent trusted peer store
                TrustedPeer existing = peers.get(clientId);
                if (existing != null && constantTimeEquals(existing.tokenSha256, providedHash)) {
                    clearFailuresLocked(bucketKey);
                    if (!existing.clientName.equals(clientName)) {
                        peers.put(clientId, new TrustedPeer(
                                clientId, clientName, existing.tokenSha256, existing.createdAtMs));
                        saveToDiskLocked();
                    }
                    return TokenVerifyResult.validPermanent();
                }

                // 2. Check single-use in-memory handover token (consumed immediately on match)
                EphemeralHandover eph = ephemeralHandovers.get(clientId);
                if (eph != null && eph.expiresAtMs >= now && constantTimeEquals(eph.tokenSha256, providedHash)) {
                    ephemeralHandovers.remove(clientId);
                    clearFailuresLocked(bucketKey);
                    return TokenVerifyResult.validEphemeral();
                }
            }

            long lockWait = checkLockoutLocked(bucketKey, now);
            if (lockWait > 0L) {
                return TokenVerifyResult.rateLimited(lockWait);
            }
            return TokenVerifyResult.needsApproval();
        }
    }

    /**
     * Persists permanent trust for {@code rawClientId} after explicit host consent ("永久允许")
     * and returns a fresh 64-char hex authToken (only its SHA-256 hash is stored on disk).
     */
    public String grantPermanentTrust(String rawClientId, String rawClientName, String peerRateLimitKey) {
        String clientId = sanitizeClientId(rawClientId);
        if (clientId.isEmpty()) return "";
        String clientName = sanitizeClientName(rawClientName, clientId);
        String bucketKey = normalizeBucketKey(peerRateLimitKey);

        synchronized (lock) {
            long now = clock.nowMs();
            clearFailuresLocked(bucketKey);
            ephemeralHandovers.remove(clientId);
            String newToken = generateRandomTokenHex(32);
            String tokenHash = CryptoHex.sha256Hex(newToken);
            if (!peers.containsKey(clientId) && peers.size() >= MAX_TRUSTED_CLIENTS) {
                String oldestKey = peers.keySet().iterator().next();
                peers.remove(oldestKey);
            }
            peers.put(clientId, new TrustedPeer(clientId, clientName, tokenHash, now));
            saveToDiskLocked();
            return newToken;
        }
    }

    /**
     * Issues a single-use, in-memory-only transfer token valid for {@link #EPHEMERAL_HANDOVER_TTL_MS}
     * (never written to disk) so an "allow once" app-catalog lookup can launch one chosen floating
     * app without prompting the host twice.
     */
    public String issueSingleUseHandoverToken(String rawClientId, String peerRateLimitKey) {
        String clientId = sanitizeClientId(rawClientId);
        if (clientId.isEmpty()) return "";
        String bucketKey = normalizeBucketKey(peerRateLimitKey);
        synchronized (lock) {
            long now = clock.nowMs();
            clearFailuresLocked(bucketKey);
            pruneExpiredEphemeralsLocked(now);
            String tok = generateRandomTokenHex(32);
            ephemeralHandovers.put(clientId, new EphemeralHandover(
                    clientId, CryptoHex.sha256Hex(tok), now + EPHEMERAL_HANDOVER_TTL_MS));
            return tok;
        }
    }

    /**
     * Issues and persists a fresh trusted 32-byte hex authToken for a peer that was already
     * authenticated over a trusted USB/ADB LocalSocket channel (used by REQUEST_WIFI_UPGRADE).
     */
    public String issueTokenForAuthenticatedPeer(String rawClientId, String rawClientName) {
        return grantPermanentTrust(rawClientId, rawClientName, "adb");
    }

    /** Records an explicit host rejection to deter LAN pairing popup spam. */
    public void recordRejection(String peerRateLimitKey) {
        String bucketKey = normalizeBucketKey(peerRateLimitKey);
        synchronized (lock) {
            recordFailureLocked(bucketKey, clock.nowMs());
        }
    }

    /** Clears failure counters when the host grants temporary or permanent access. */
    public void clearFailures(String peerRateLimitKey) {
        String bucketKey = normalizeBucketKey(peerRateLimitKey);
        synchronized (lock) {
            clearFailuresLocked(bucketKey);
        }
    }

    public boolean isTrusted(String rawClientId) {
        String cid = sanitizeClientId(rawClientId);
        if (cid.isEmpty()) return false;
        synchronized (lock) {
            return peers.containsKey(cid);
        }
    }

    /**
     * Verifies a BLE Instant Hotspot challenge-response HMAC in constant time against the
     * persisted {@code tokenSha256} for {@code rawClientId}. Strictly fail-closed.
     */
    public boolean verifyHotspotHmac(String rawClientId, String nonceHex, String providedHmacHex) {
        if (rawClientId == null || nonceHex == null || providedHmacHex == null) {
            return false;
        }
        String trimmedId = rawClientId.trim();
        String sanitizedId = sanitizeClientId(trimmedId);
        if (sanitizedId.isEmpty() || !sanitizedId.equals(trimmedId)) {
            return false;
        }
        String storedTokenSha256;
        synchronized (lock) {
            TrustedPeer peer = peers.get(sanitizedId);
            if (peer == null || peer.tokenSha256 == null || peer.tokenSha256.isEmpty()) {
                return false;
            }
            storedTokenSha256 = peer.tokenSha256;
        }
        return BleNearbyProtocol.verifyHotspotHmacWithTokenHash(
                storedTokenSha256, sanitizedId, nonceHex, providedHmacHex);
    }

    public int getTrustedCount() {
        synchronized (lock) {
            return peers.size();
        }
    }

    public List<TrustedPeer> listTrustedPeers() {
        synchronized (lock) {
            return new ArrayList<>(peers.values());
        }
    }

    public String toTrustedPeersJson() {
        synchronized (lock) {
            SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
            for (TrustedPeer p : peers.values()) {
                arr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("clientId", p.clientId)
                        .put("clientName", p.clientName)
                        .put("createdAtMs", p.createdAtMs)
                        .build());
            }
            return arr.build();
        }
    }

    /**
     * Formats trusted clients per summary contract: {@code clientId=clientName;...}
     */
    public String listTrustedClientsSummary() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            for (TrustedPeer p : peers.values()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(p.clientId).append('=').append(p.clientName);
            }
            return sb.toString();
        }
    }

    /**
     * Revokes a single clientId or clears all trusted clients when {@code "*"} is passed.
     */
    public boolean revokeClient(String rawClientId) {
        if (rawClientId == null) return false;
        String trimmed = rawClientId.trim();
        synchronized (lock) {
            if ("*".equals(trimmed)) {
                peers.clear();
                ephemeralHandovers.clear();
                saveToDiskLocked();
                return true;
            }
            String cid = sanitizeClientId(trimmed);
            if (cid.isEmpty()) return false;
            ephemeralHandovers.remove(cid);
            boolean removed = (peers.remove(cid) != null);
            if (removed) {
                saveToDiskLocked();
            }
            return removed;
        }
    }

    /**
     * Constant-time string equality via SHA-256 digest comparison to eliminate
     * both character-position and string-length timing side channels.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] da = md.digest(a.getBytes(StandardCharsets.UTF_8));
            md.reset();
            byte[] db = md.digest(b.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(da, db);
        } catch (Throwable t) {
            byte[] ba = a.getBytes(StandardCharsets.UTF_8);
            byte[] bb = b.getBytes(StandardCharsets.UTF_8);
            int diff = ba.length ^ bb.length;
            int max = Math.max(ba.length, bb.length);
            for (int i = 0; i < max; i++) {
                byte x = (i < ba.length) ? ba[i] : 0;
                byte y = (i < bb.length) ? bb[i] : 0;
                diff |= (x ^ y);
            }
            return diff == 0;
        }
    }

    public static String sanitizeClientId(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 96));
        for (int i = 0; i < raw.length() && sb.length() < 96; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == ':') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String sanitizeClientName(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback != null ? fallback : "Device";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 64));
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '=' || c == ';' || c == '\t' || c == '\r' || c == '\n') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() ? (fallback != null ? fallback : "Device") : cleaned;
    }

    private static String normalizeBucketKey(String key) {
        return (key == null || key.trim().isEmpty()) ? "unknown" : key.trim();
    }

    private String generateRandomTokenHex(int numBytes) {
        byte[] buf = new byte[numBytes];
        rng.nextBytes(buf);
        return CryptoHex.toHex(buf);
    }

    private void pruneExpiredEphemeralsLocked(long now) {
        if (ephemeralHandovers.isEmpty()) return;
        Iterator<Map.Entry<String, EphemeralHandover>> it = ephemeralHandovers.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMs < now) {
                it.remove();
            }
        }
    }

    private long checkLockoutLocked(String bucketKey, long now) {
        long globalRem = Math.max(0L, globalFailures.lockedUntilMs - now);
        FailureBucket peer = failureBuckets.get(bucketKey);
        long peerRem = (peer != null) ? Math.max(0L, peer.lockedUntilMs - now) : 0L;
        return Math.max(globalRem, peerRem);
    }

    private void recordFailureLocked(String bucketKey, long now) {
        FailureBucket peer = failureBuckets.computeIfAbsent(bucketKey, k -> new FailureBucket());
        incrementBucket(peer, now);
        incrementBucket(globalFailures, now);
        if (failureBuckets.size() > 128) {
            failureBuckets.entrySet().removeIf(e ->
                    e.getValue().lockedUntilMs <= now && (now - e.getValue().lastFailureMs) > FAILURE_WINDOW_RESET_MS);
        }
    }

    private static void incrementBucket(FailureBucket b, long now) {
        if (b.lastFailureMs > 0 && (now - b.lastFailureMs) > FAILURE_WINDOW_RESET_MS) {
            b.consecutiveFailures = 0;
            b.lockedUntilMs = 0L;
        }
        b.consecutiveFailures++;
        b.lastFailureMs = now;
        if (b.consecutiveFailures >= MAX_FAILURES_BEFORE_LOCKOUT) {
            int shift = Math.min(4, b.consecutiveFailures - MAX_FAILURES_BEFORE_LOCKOUT);
            long dur = Math.min(MAX_LOCKOUT_MS, BASE_LOCKOUT_MS << shift);
            b.lockedUntilMs = Math.max(b.lockedUntilMs, now + dur);
        }
    }

    private void clearFailuresLocked(String bucketKey) {
        failureBuckets.remove(bucketKey);
        globalFailures.consecutiveFailures = 0;
        globalFailures.lockedUntilMs = 0L;
    }

    private void loadFromDisk() {
        if (storeFile == null || !storeFile.isFile()) return;
        synchronized (lock) {
            peers.clear();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(storeFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    String[] parts = trimmed.split("\t", -1);
                    if (parts.length < 3) continue;
                    String cid = sanitizeClientId(parts[0]);
                    String name = sanitizeClientName(parts[1], cid);
                    String tokenHash = parts[2].trim().toLowerCase(Locale.US);
                    long created = 0L;
                    if (parts.length >= 4) {
                        try {
                            created = Long.parseLong(parts[3].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (!cid.isEmpty() && tokenHash.length() == 64 && peers.size() < MAX_TRUSTED_CLIENTS) {
                        peers.put(cid, new TrustedPeer(cid, name, tokenHash, created));
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("OPALDESK_TRUSTSTORE_LOAD_ERR path=" + storeFile.getAbsolutePath()
                        + ": " + e.getMessage());
            }
        }
    }

    private void saveToDiskLocked() {
        if (storeFile == null) return;
        StringBuilder sb = new StringBuilder();
        for (TrustedPeer p : peers.values()) {
            sb.append(p.clientId).append('\t')
              .append(p.clientName).append('\t')
              .append(p.tokenSha256).append('\t')
              .append(p.createdAtMs).append('\n');
        }
        try {
            AtomicFiles.writeAtomicUtf8(storeFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
        } catch (IOException | RuntimeException e) {
            System.err.println("OPALDESK_TRUSTSTORE_SAVE_ERR path=" + storeFile.getAbsolutePath()
                    + ": " + e.getMessage());
        }
    }
}
