package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared BLE Nearby Discovery & Instant Hotspot wire constants, 12-byte manufacturer payload
 * codec, and constant-time HMAC-SHA256 challenge/response authenticator (OpalDesk v1.0.23).
 *
 * <p>Canonical HMAC derivation uses {@code key = UTF8(tokenSha256)} where {@code tokenSha256} is
 * the 64-char lowercase hex {@code SHA-256(UTF8(authToken))} already persisted by
 * {@code TrustStoreManager} (so plaintext {@code authToken} never touches disk on Android).
 */
public final class BleNearbyProtocol {
    public static final String SERVICE_UUID = "7A4F0001-6F70-616C-6465-736B00000001";
    public static final String CHAR_CHALLENGE_UUID = "7A4F0002-6F70-616C-6465-736B00000001";
    public static final String CHAR_COMMAND_UUID = "7A4F0003-6F70-616C-6465-736B00000001";

    public static final int MANUFACTURER_ID = 0x504F;
    public static final int PROTOCOL_VERSION = 1;
    public static final int MANUFACTURER_DATA_LENGTH = 12;

    public static final int CAP_LAN_SHARE = 0x01;
    public static final int CAP_HOTSPOT_ACTIVE = 0x02;
    public static final int CAP_HOTSPOT_TRIGGER = 0x04;

    private static final String HOTSPOT_HMAC_DOMAIN = "OPAL_HOTSPOT_V1:";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int FP_PREFIX_BYTES = 8;
    private static final int HMAC_HEX_LENGTH = 64;

    private BleNearbyProtocol() {}

    public static final class AdvertisementInfo {
        public final int version;
        public final int capFlags;
        public final String certFingerprintPrefixHex;
        public final int wifiPort;
        public final int batteryPct; // 0..100, or -1 when unknown (0xFF)

        public AdvertisementInfo(
                int version,
                int capFlags,
                String certFingerprintPrefixHex,
                int wifiPort,
                int batteryPct) {
            this.version = version;
            this.capFlags = capFlags & 0x0F;
            this.certFingerprintPrefixHex = (certFingerprintPrefixHex != null) ? certFingerprintPrefixHex : "";
            this.wifiPort = wifiPort & 0xFFFF;
            this.batteryPct = (batteryPct >= 0 && batteryPct <= 100) ? batteryPct : -1;
        }

        public boolean isSupportedVersion() {
            return version == PROTOCOL_VERSION;
        }

        public boolean hasLanShare() {
            return (capFlags & CAP_LAN_SHARE) != 0;
        }

        public boolean isHotspotActive() {
            return (capFlags & CAP_HOTSPOT_ACTIVE) != 0;
        }

        public boolean canTriggerHotspot() {
            return (capFlags & CAP_HOTSPOT_TRIGGER) != 0;
        }
    }

    /**
     * Encodes the exact 12-byte BLE Manufacturer Specific Data payload:
     * <ul>
     *   <li>byte 0: {@code ((PROTOCOL_VERSION & 0x0F) << 4) | (capFlags & 0x0F)}</li>
     *   <li>bytes 1..8: first 8 raw bytes (16 hex chars) of normalized cert SHA-256 fingerprint (zero-padded)</li>
     *   <li>bytes 9..10: {@code wifiPort} Big-Endian uint16</li>
     *   <li>byte 11: {@code batteryPct} (0..100, else {@code 0xFF})</li>
     * </ul>
     */
    public static byte[] encodeManufacturerData(
            int capFlags, String certFingerprintHex, int wifiPort, int batteryPct) {
        byte[] out = new byte[MANUFACTURER_DATA_LENGTH];
        out[0] = (byte) (((PROTOCOL_VERSION & 0x0F) << 4) | (capFlags & 0x0F));

        byte[] fpPrefix = parseHexPrefixBytes(certFingerprintHex, FP_PREFIX_BYTES);
        System.arraycopy(fpPrefix, 0, out, 1, FP_PREFIX_BYTES);

        int clampedPort = Math.max(0, Math.min(0xFFFF, wifiPort));
        out[9] = (byte) ((clampedPort >>> 8) & 0xFF);
        out[10] = (byte) (clampedPort & 0xFF);

        out[11] = (batteryPct >= 0 && batteryPct <= 100) ? (byte) batteryPct : (byte) 0xFF;
        return out;
    }

    /**
     * Decodes a 12-byte BLE Manufacturer Specific Data payload, returning {@code null} when null or truncated.
     */
    public static AdvertisementInfo decodeManufacturerData(byte[] data) {
        if (data == null || data.length < MANUFACTURER_DATA_LENGTH) {
            return null;
        }
        int header = data[0] & 0xFF;
        int version = (header >>> 4) & 0x0F;
        int capFlags = header & 0x0F;

        byte[] fpBytes = new byte[FP_PREFIX_BYTES];
        System.arraycopy(data, 1, fpBytes, 0, FP_PREFIX_BYTES);
        String fpHex = CryptoHex.toHex(fpBytes);

        int wifiPort = ((data[9] & 0xFF) << 8) | (data[10] & 0xFF);
        int rawBattery = data[11] & 0xFF;
        int batteryPct = (rawBattery <= 100) ? rawBattery : -1;

        return new AdvertisementInfo(version, capFlags, fpHex, wifiPort, batteryPct);
    }

    /**
     * Computes {@code HMAC-SHA256(key = UTF8(sha256HexLower(authToken)),
     * msg = UTF8("OPAL_HOTSPOT_V1:" + clientId.trim() + ":" + nonceHex.trim().toLowerCase()))}
     * as lowercase 64-char hex. Returns empty string on blank input.
     */
    public static String computeHotspotHmacHex(String authToken, String clientId, String nonceHex) {
        if (authToken == null || authToken.isEmpty()) {
            return "";
        }
        String tokenSha256 = CryptoHex.sha256Hex(authToken);
        return computeHotspotHmacFromTokenHashHex(tokenSha256, clientId, nonceHex);
    }

    /**
     * Computes {@code HMAC-SHA256(key = UTF8(tokenSha256Lower),
     * msg = UTF8("OPAL_HOTSPOT_V1:" + clientId.trim() + ":" + nonceHex.trim().toLowerCase()))}
     * directly from a trusted peer's persisted {@code tokenSha256}.
     */
    public static String computeHotspotHmacFromTokenHashHex(
            String tokenSha256, String clientId, String nonceHex) {
        if (tokenSha256 == null || clientId == null || nonceHex == null) {
            return "";
        }
        String normalizedKey = tokenSha256.trim().toLowerCase(Locale.ROOT);
        String normalizedClientId = clientId.trim();
        String normalizedNonce = nonceHex.trim().toLowerCase(Locale.ROOT);
        if (normalizedKey.isEmpty() || normalizedClientId.isEmpty() || normalizedNonce.isEmpty()) {
            return "";
        }
        String message = HOTSPOT_HMAC_DOMAIN + normalizedClientId + ":" + normalizedNonce;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(normalizedKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return CryptoHex.toHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Constant-time HMAC verification against a raw {@code authToken}.
     */
    public static boolean verifyHotspotHmac(
            String authToken, String clientId, String nonceHex, String providedHmacHex) {
        if (authToken == null || authToken.isEmpty()) {
            return false;
        }
        return verifyHotspotHmacWithTokenHash(
                CryptoHex.sha256Hex(authToken), clientId, nonceHex, providedHmacHex);
    }

    /**
     * Constant-time HMAC verification directly against a persisted {@code tokenSha256} from
     * {@code TrustStoreManager} using {@link MessageDigest#isEqual(byte[], byte[])}.
     */
    public static boolean verifyHotspotHmacWithTokenHash(
            String tokenSha256, String clientId, String nonceHex, String providedHmacHex) {
        if (providedHmacHex == null) {
            return false;
        }
        String normalizedProvided = providedHmacHex.trim().toLowerCase(Locale.ROOT);
        if (normalizedProvided.length() != HMAC_HEX_LENGTH) {
            return false;
        }
        String expected = computeHotspotHmacFromTokenHashHex(tokenSha256, clientId, nonceHex);
        if (expected.length() != HMAC_HEX_LENGTH) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                normalizedProvided.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] parseHexPrefixBytes(String rawHex, int numBytes) {
        byte[] out = new byte[numBytes];
        if (rawHex == null || rawHex.isEmpty()) {
            return out;
        }
        StringBuilder cleaned = new StringBuilder(numBytes * 2);
        for (int i = 0; i < rawHex.length() && cleaned.length() < numBytes * 2; i++) {
            char c = rawHex.charAt(i);
            if ((c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')) {
                cleaned.append(c);
            }
        }
        int pairCount = cleaned.length() / 2;
        for (int i = 0; i < pairCount; i++) {
            int hi = Character.digit(cleaned.charAt(i * 2), 16);
            int lo = Character.digit(cleaned.charAt(i * 2 + 1), 16);
            if (hi >= 0 && lo >= 0) {
                out[i] = (byte) ((hi << 4) | lo);
            }
        }
        return out;
    }
}
