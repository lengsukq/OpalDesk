package io.github.lengsukq.opaldesk.daemon.ipc;

import io.github.lengsukq.opaldesk.daemon.util.CryptoHex;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HelperPackageResolverTest {

    @Test
    public void parseUserIdsFromPmListUsersPrioritizesUserZeroAndExtractsSecondaryUsers() {
        String output = ""
                + "Users:\n"
                + "\tUserInfo{10:Work profile:1030} running\n"
                + "\tUserInfo{0:Owner:4c13} running\n"
                + "\tUserInfo{999:XSpace:2000010} running\n"
                + "\tUserInfo{0:Duplicate:4c13}\n";

        assertArrayEquals(new int[]{0, 10, 999}, HelperPackageResolver.parseUserIdsFromPmListUsers(output));
        assertArrayEquals(new int[0], HelperPackageResolver.parseUserIdsFromPmListUsers(null));
        assertArrayEquals(new int[0], HelperPackageResolver.parseUserIdsFromPmListUsers("no users here"));
    }

    @Test
    public void parsePackageUidFromPmListPackagesMatchesExactPackageName() {
        String output = ""
                + "package:io.github.lengsukq.opaldesk.debug uid:10999\n"
                + "package:io.github.lengsukq.opaldesk.other uid:10888\n"
                + "package:io.github.lengsukq.opaldesk uid:99910345\n";

        assertEquals(99910345, HelperPackageResolver.parsePackageUidFromPmListPackages(
                output, "io.github.lengsukq.opaldesk"));
        assertEquals(-1, HelperPackageResolver.parsePackageUidFromPmListPackages(
                output, "io.github.lengsukq.opaldesk.missing"));
        assertEquals(-1, HelperPackageResolver.parsePackageUidFromPmListPackages(
                "package:io.github.lengsukq.opaldesk uid:notanumber\n",
                "io.github.lengsukq.opaldesk"));
    }

    @Test
    public void parseApkPathsFromPmPathRejectsUntrustedLocationsAndPrioritizesBaseApk() {
        String output = ""
                + "package:/data/local/tmp/spoofed.apk\n"
                + "package:/sdcard/Download/opaldesk.apk\n"
                + "package:/data/app/../../tmp/evil.apk\n"
                + "package:/data/app/~~abc==/io.github.lengsukq.opaldesk-xyz==/split_config.arm64_v8a.apk\n"
                + "package:/data/app/~~abc==/io.github.lengsukq.opaldesk-xyz==/base.apk\n";

        assertEquals(
                Arrays.asList(
                        "/data/app/~~abc==/io.github.lengsukq.opaldesk-xyz==/base.apk",
                        "/data/app/~~abc==/io.github.lengsukq.opaldesk-xyz==/split_config.arm64_v8a.apk"),
                HelperPackageResolver.parseApkPathsFromPmPath(output));
        assertEquals(Collections.emptyList(), HelperPackageResolver.parseApkPathsFromPmPath(null));

        assertTrue(HelperPackageResolver.isTrustedInstalledApkPath("/mnt/expand/vol1/app/pkg/base.apk"));
        assertFalse(HelperPackageResolver.isTrustedInstalledApkPath("/data/local/tmp/base.apk"));
        assertFalse(HelperPackageResolver.isTrustedInstalledApkPath("relative/base.apk"));
        assertFalse(HelperPackageResolver.isTrustedInstalledApkPath("/data/app/not_an_apk.jar"));
    }

    @Test
    public void verifySignerDigestsEnforcesSingleMatchingCertificateFailClosed() {
        byte[] trustedBytes = "trusted-helper-cert".getBytes(StandardCharsets.UTF_8);
        byte[] evilBytes = "evil-cert".getBytes(StandardCharsets.UTF_8);
        String expectedHex = CryptoHex.sha256Hex(trustedBytes);

        // 1. Valid single SigningInfo certificate
        HelperPackageResolver.CertCheckResult ok = HelperPackageResolver.verifySignerDigests(
                true, false, new byte[][]{trustedBytes}, null, expectedHex.toUpperCase());
        assertEquals(HelperPackageResolver.CertCheckStatus.VERIFIED, ok.status);

        // 2. Wrong cert fails closed with CERT_MISMATCH
        HelperPackageResolver.CertCheckResult wrong = HelperPackageResolver.verifySignerDigests(
                true, false, new byte[][]{evilBytes}, null, expectedHex);
        assertEquals(HelperPackageResolver.CertCheckStatus.CERT_MISMATCH, wrong.status);
        assertEquals(CryptoHex.sha256Hex(evilBytes), wrong.actualSha256);

        // 3. Multi-signer APK rejected even if one signer matches
        HelperPackageResolver.CertCheckResult multiFlag = HelperPackageResolver.verifySignerDigests(
                true, true, new byte[][]{trustedBytes}, null, expectedHex);
        assertEquals(HelperPackageResolver.CertCheckStatus.MULTI_SIGNER_REJECTED, multiFlag.status);

        HelperPackageResolver.CertCheckResult multiArray = HelperPackageResolver.verifySignerDigests(
                true, false, new byte[][]{trustedBytes, evilBytes}, null, expectedHex);
        assertEquals(HelperPackageResolver.CertCheckStatus.MULTI_SIGNER_REJECTED, multiArray.status);

        // 4. Legacy signature fallback allowed ONLY when SigningInfo is absent
        HelperPackageResolver.CertCheckResult legacyOk = HelperPackageResolver.verifySignerDigests(
                false, false, null, new byte[][]{trustedBytes}, expectedHex);
        assertEquals(HelperPackageResolver.CertCheckStatus.VERIFIED, legacyOk.status);

        HelperPackageResolver.CertCheckResult missingBoth = HelperPackageResolver.verifySignerDigests(
                false, false, null, null, expectedHex);
        assertEquals(HelperPackageResolver.CertCheckStatus.MISSING_SIGNING_INFO, missingBoth.status);
    }

    @Test
    public void hasVerifiedProviderAuthorityValidatesOwnerEnabledAndAuthority() {
        assertFalse(HelperPackageResolver.isMatchingProviderAuthority(
                "com.evil.spoof",
                BridgeProtocol.HELPER_PROVIDER_AUTHORITY,
                true,
                "io.github.lengsukq.opaldesk",
                BridgeProtocol.HELPER_PROVIDER_AUTHORITY));
        assertFalse(HelperPackageResolver.isMatchingProviderAuthority(
                "io.github.lengsukq.opaldesk",
                BridgeProtocol.HELPER_PROVIDER_AUTHORITY,
                false,
                "io.github.lengsukq.opaldesk",
                BridgeProtocol.HELPER_PROVIDER_AUTHORITY));
        assertFalse(HelperPackageResolver.isMatchingProviderAuthority(
                "io.github.lengsukq.opaldesk",
                "io.github.lengsukq.opaldesk.other",
                true,
                "io.github.lengsukq.opaldesk",
                BridgeProtocol.HELPER_PROVIDER_AUTHORITY));
        assertTrue(HelperPackageResolver.isMatchingProviderAuthority(
                "io.github.lengsukq.opaldesk",
                "io.github.lengsukq.opaldesk.other; " + BridgeProtocol.HELPER_PROVIDER_AUTHORITY,
                true,
                "io.github.lengsukq.opaldesk",
                BridgeProtocol.HELPER_PROVIDER_AUTHORITY));
        assertEquals(0, HelperPackageResolver.extractUserIdFromUid(10345));
        assertEquals(999, HelperPackageResolver.extractUserIdFromUid(99910345));
        assertEquals(-1, HelperPackageResolver.extractUserIdFromUid(-1));
    }
}
