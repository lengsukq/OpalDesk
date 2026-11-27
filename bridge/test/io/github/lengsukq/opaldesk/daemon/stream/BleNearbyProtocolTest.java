package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.security.TrustStoreManager;
import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BleNearbyProtocolTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testConstantsAnd12ByteManufacturerDataEncodeDecode() {
        assertEquals("7A4F0001-6F70-616C-6465-736B-000000000001", BleNearbyProtocol.SERVICE_UUID);
        assertEquals("7A4F0002-6F70-616C-6465-736B-000000000001", BleNearbyProtocol.CHAR_CHALLENGE_UUID);
        assertEquals("7A4F0003-6F70-616C-6465-736B-000000000001", BleNearbyProtocol.CHAR_COMMAND_UUID);
        assertEquals(0x504F, BleNearbyProtocol.MANUFACTURER_ID);
        assertEquals(1, BleNearbyProtocol.PROTOCOL_VERSION);

        int caps = BleNearbyProtocol.CAP_LAN_SHARE | BleNearbyProtocol.CAP_HOTSPOT_TRIGGER;
        String fp = "AA:BB:CC:DD:EE:FF:01:23:45:67:89:AB";
        byte[] payload = BleNearbyProtocol.encodeManufacturerData(caps, fp, 19878, 87);
        assertEquals(12, payload.length);

        // Byte 0: (PROTOCOL_VERSION << 4) | capFlags = (1 << 4) | 0x05 = 0x15
        assertEquals((byte) 0x15, payload[0]);
        // Bytes 1..8: AA BB CC DD EE FF 01 23
        assertEquals((byte) 0xAA, payload[1]);
        assertEquals((byte) 0x23, payload[8]);
        // Bytes 9..10: 19878 = 0x4DA6 big-endian
        assertEquals((byte) 0x4D, payload[9]);
        assertEquals((byte) 0xA6, payload[10]);
        // Byte 11: battery 87
        assertEquals((byte) 87, payload[11]);

        BleNearbyProtocol.AdvertisementInfo decoded = BleNearbyProtocol.decodeManufacturerData(payload);
        assertNotNull(decoded);
        assertTrue(decoded.isSupportedVersion());
        assertEquals(1, decoded.version);
        assertEquals(caps, decoded.capFlags);
        assertTrue(decoded.hasLanShare());
        assertFalse(decoded.isHotspotActive());
        assertTrue(decoded.canTriggerHotspot());
        assertEquals("aabbccddeeff0123", decoded.certFingerprintPrefixHex);
        assertEquals(19878, decoded.wifiPort);
        assertEquals(87, decoded.batteryPct);

        // Short fingerprint zero-padding & unknown battery (0xFF -> -1)
        byte[] shortPayload = BleNearbyProtocol.encodeManufacturerData(
                BleNearbyProtocol.CAP_HOTSPOT_ACTIVE, "1234", 8080, -1);
        assertEquals((byte) 0xFF, shortPayload[11]);
        BleNearbyProtocol.AdvertisementInfo shortDec = BleNearbyProtocol.decodeManufacturerData(shortPayload);
        assertNotNull(shortDec);
        assertEquals("1234000000000000", shortDec.certFingerprintPrefixHex);
        assertEquals(8080, shortDec.wifiPort);
        assertEquals(-1, shortDec.batteryPct);
        assertTrue(shortDec.isHotspotActive());

        // Truncated or null returns null
        assertNull(BleNearbyProtocol.decodeManufacturerData(null));
        assertNull(BleNearbyProtocol.decodeManufacturerData(new byte[11]));
    }

    @Test
    public void testCanonicalHotspotHmacDerivationConstantTimeVerifyAndTamperRejection() throws Exception {
        String authToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String clientId = "mac-pro-01";
        String nonceHex = "AABBCCDD00112233445566778899EEFF";

        String tokenSha256 = CryptoHex.sha256Hex(authToken);
        Mac refMac = Mac.getInstance("HmacSHA256");
        refMac.init(new SecretKeySpec(tokenSha256.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expectedHmac = CryptoHex.toHex(refMac.doFinal(
                ("OPAL_HOTSPOT_V1:mac-pro-01:aabbccdd00112233445566778899eeff").getBytes(StandardCharsets.UTF_8)));

        String computed = BleNearbyProtocol.computeHotspotHmacHex(authToken, "  mac-pro-01 ", nonceHex);
        assertEquals(64, computed.length());
        assertEquals(expectedHmac, computed);
        assertEquals(expectedHmac, BleNearbyProtocol.computeHotspotHmacFromTokenHashHex(tokenSha256, clientId, nonceHex));

        // Exact and uppercase hex verify cleanly
        assertTrue(BleNearbyProtocol.verifyHotspotHmac(authToken, clientId, nonceHex, computed));
        assertTrue(BleNearbyProtocol.verifyHotspotHmacWithTokenHash(
                tokenSha256, clientId, nonceHex.toLowerCase(), computed.toUpperCase()));

        // Tamper rejections
        char flipped = computed.charAt(0) == 'a' ? 'b' : 'a';
        String tamperedHmac = flipped + computed.substring(1);
        assertFalse(BleNearbyProtocol.verifyHotspotHmac(authToken, clientId, nonceHex, tamperedHmac));
        assertFalse(BleNearbyProtocol.verifyHotspotHmac(authToken, "mac-pro-02", nonceHex, computed));
        assertFalse(BleNearbyProtocol.verifyHotspotHmac(authToken, clientId, "0000", computed));
        assertFalse(BleNearbyProtocol.verifyHotspotHmac("wrong-token", clientId, nonceHex, computed));
        assertFalse(BleNearbyProtocol.verifyHotspotHmac(authToken, clientId, nonceHex, ""));
        assertFalse(BleNearbyProtocol.verifyHotspotHmac(authToken, clientId, nonceHex, null));
        assertFalse(BleNearbyProtocol.verifyHotspotHmac("", clientId, nonceHex, computed));
    }

    @Test
    public void testTrustStoreVerifyHotspotHmacWorksAcrossDiskReloadAndRejectsRevoked() throws Exception {
        File storeFile = new File(tmpDir.newFolder(), "trusted_ble.conf");
        TrustStoreManager mgr = new TrustStoreManager(storeFile);
        String rawAuthToken = mgr.grantPermanentTrust("mac-ble-1", "MacBook Pro", "192.168.1.5");
        String nonce = "deadbeefcafebabe0123456789abcdef";
        String validHmac = BleNearbyProtocol.computeHotspotHmacHex(rawAuthToken, "mac-ble-1", nonce);

        // Reload from disk (only tokenSha256 exists on disk!) and verify HMAC succeeds
        TrustStoreManager reloaded = new TrustStoreManager(storeFile);
        assertTrue(reloaded.verifyHotspotHmac("mac-ble-1", nonce, validHmac));
        assertFalse(reloaded.verifyHotspotHmac("unknown-mac", nonce, validHmac));
        assertFalse(reloaded.verifyHotspotHmac("mac-ble-1", "other-nonce", validHmac));
        assertFalse(reloaded.verifyHotspotHmac("", nonce, validHmac));

        // After revocation, HMAC must immediately fail
        assertTrue(reloaded.revokeClient("mac-ble-1"));
        assertFalse(reloaded.verifyHotspotHmac("mac-ble-1", nonce, validHmac));
    }
}
