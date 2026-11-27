package io.github.lengsukq.opaldesk.daemon.stream;

import io.github.lengsukq.opaldesk.daemon.util.ShellCommand;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteApkInstallerTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testCommandBuildersAndSessionIdParsing() {
        assertArrayEquals(
                new String[]{"pm", "install-create", "-r", "-d", "-S", "12345"},
                RemoteApkInstaller.buildInstallCreateCommand(12345L, true, true));
        assertArrayEquals(
                new String[]{"pm", "install-create", "-S", "4096"},
                RemoteApkInstaller.buildInstallCreateCommand(4096L, false, false));
        assertArrayEquals(
                new String[]{"pm", "install-write", "-S", "1024", "88", "0_base.apk", "/tmp/base.apk"},
                RemoteApkInstaller.buildInstallWriteCommand(88, 1024L, "0_base.apk", "/tmp/base.apk"));
        assertArrayEquals(
                new String[]{"pm", "install-commit", "88"},
                RemoteApkInstaller.buildInstallCommitCommand(88));
        assertArrayEquals(
                new String[]{"pm", "install-abandon", "88"},
                RemoteApkInstaller.buildInstallAbandonCommand(88));
        assertEquals(90421, RemoteApkInstaller.parseSessionId("Success: created install session [90421]\n"));
        assertEquals(-1, RemoteApkInstaller.parseSessionId("Failure [INSTALL_FAILED_INTERNAL_ERROR]"));
    }

    @Test
    public void testSingleApkInstallRunsPmSessionAndCleansStagedFile() throws Exception {
        File stageRoot = tmpDir.newFolder("stage");
        File workRoot = tmpDir.newFolder("work");
        File obbRoot = tmpDir.newFolder("sdcard");
        File apkFile = new File(stageRoot, "sample.apk");
        Files.write(apkFile.toPath(), "FAKE_APK_BYTES".getBytes(StandardCharsets.UTF_8));

        List<List<String>> recordedCmds = new ArrayList<>();
        RemoteApkInstaller installer = new RemoteApkInstaller(
                Collections.singletonList(stageRoot),
                workRoot,
                stageRoot,
                obbRoot,
                (cmd, timeoutMs) -> {
                    recordedCmds.add(Arrays.asList(cmd));
                    if ("install-create".equals(cmd[1])) {
                        return new ShellCommand.Result(0, "Success: created install session [77]\n", false);
                    }
                    return new ShellCommand.Result(0, "Success\n", false);
                },
                RemoteApkInstaller.MAX_TOTAL_EXTRACTED_BYTES);

        List<OpalCastProtocol.ApkInstallStatusMsg> statuses = new ArrayList<>();
        boolean ok = installer.install(
                new OpalCastProtocol.ApkInstallRequestMsg(1, apkFile.getAbsolutePath(), "apk", true, false),
                statuses::add);

        assertTrue(ok);
        assertEquals(3, recordedCmds.size());
        assertEquals("install-create", recordedCmds.get(0).get(1));
        assertEquals("install-write", recordedCmds.get(1).get(1));
        assertEquals("77", recordedCmds.get(1).get(4));
        assertEquals("install-commit", recordedCmds.get(2).get(1));
        assertEquals("succeeded", statuses.get(statuses.size() - 1).stage);
        assertEquals(100, statuses.get(statuses.size() - 1).progress);
        assertFalse("Staged APK must be cleaned up after install", apkFile.exists());
    }

    @Test
    public void testXapkBundleSplitsOrderingObbCopyAndTempCleanup() throws Exception {
        File sdcard = tmpDir.newFolder("sdcard_xapk");
        File workRoot = tmpDir.newFolder("work_xapk");
        File xapk = new File(sdcard, "game.xapk");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(xapk))) {
            writeZipEntry(zos, "manifest.json", "{\"package_name\":\"com.example.game\"}");
            writeZipEntry(zos, "config.arm64_v8a.apk", "ARM64_SPLIT");
            writeZipEntry(zos, "base.apk", "BASE_APK_DATA");
            writeZipEntry(zos, "Android/obb/com.example.game/main.1.com.example.game.obb", "OBB_PAYLOAD");
            writeZipEntry(zos, "__MACOSX/._base.apk", "JUNK");
        }

        List<List<String>> cmds = new ArrayList<>();
        RemoteApkInstaller installer = new RemoteApkInstaller(
                Collections.singletonList(sdcard),
                workRoot,
                null,
                sdcard,
                (cmd, timeoutMs) -> {
                    cmds.add(Arrays.asList(cmd));
                    if ("install-create".equals(cmd[1])) {
                        return new ShellCommand.Result(0, "Success: created install session [321]\n", false);
                    }
                    return new ShellCommand.Result(0, "Success\n", false);
                },
                RemoteApkInstaller.MAX_TOTAL_EXTRACTED_BYTES);

        List<OpalCastProtocol.ApkInstallStatusMsg> statuses = new ArrayList<>();
        boolean ok = installer.install(
                new OpalCastProtocol.ApkInstallRequestMsg(2, xapk.getAbsolutePath(), "xapk", true, true),
                statuses::add);

        assertTrue(ok);
        // 1 create + 2 writes (base.apk first, then config.arm64_v8a.apk) + 1 commit
        assertEquals(4, cmds.size());
        assertTrue(cmds.get(1).get(5).endsWith("base.apk"));
        assertTrue(cmds.get(2).get(5).endsWith("config.arm64_v8a.apk"));
        assertEquals("com.example.game", statuses.get(statuses.size() - 1).packageName);
        assertEquals("succeeded", statuses.get(statuses.size() - 1).stage);

        // OBB copied safely into Android/obb/<pkg>/
        File installedObb = new File(sdcard, "Android/obb/com.example.game/main.1.com.example.game.obb");
        assertTrue(installedObb.isFile());
        assertEquals("OBB_PAYLOAD", new String(Files.readAllBytes(installedObb.toPath()), StandardCharsets.UTF_8));

        // Work directory thoroughly cleaned in finally
        File[] leftovers = workRoot.listFiles();
        assertEquals(0, leftovers != null ? leftovers.length : 0);
    }

    @Test
    public void testZipSlipDecompressionBombAndOutsidePathAreBlockedAndCleanedUp() throws Exception {
        File sdcard = tmpDir.newFolder("sdcard_sec");
        File workRoot = tmpDir.newFolder("work_sec");
        File outside = tmpDir.newFile("outside.apk");
        Files.write(outside.toPath(), "OUTSIDE".getBytes(StandardCharsets.UTF_8));

        RemoteApkInstaller installer = new RemoteApkInstaller(
                Collections.singletonList(sdcard),
                workRoot,
                null,
                sdcard,
                (cmd, timeoutMs) -> new ShellCommand.Result(0, "Success\n", false),
                64L); // 64-byte extraction cap for bomb test

        // 1. Outside allowed roots rejected
        List<OpalCastProtocol.ApkInstallStatusMsg> s1 = new ArrayList<>();
        assertFalse(installer.install(
                new OpalCastProtocol.ApkInstallRequestMsg(10, outside.getAbsolutePath(), "apk", true, false),
                s1::add));
        assertEquals("failed", s1.get(s1.size() - 1).stage);
        assertTrue(s1.get(s1.size() - 1).message.contains("outside allowed"));

        // 2. Zip Slip blocked and temp dir cleaned up
        File zipSlip = new File(sdcard, "evil.apks");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipSlip))) {
            writeZipEntry(zos, "../../pwned.apk", "EVIL");
        }
        List<OpalCastProtocol.ApkInstallStatusMsg> s2 = new ArrayList<>();
        assertFalse(installer.install(
                new OpalCastProtocol.ApkInstallRequestMsg(11, zipSlip.getAbsolutePath(), "apks", true, false),
                s2::add));
        assertEquals("failed", s2.get(s2.size() - 1).stage);
        assertTrue(s2.get(s2.size() - 1).message.contains("Zip Slip"));
        File[] leftoversAfterSlip = workRoot.listFiles();
        assertEquals(0, leftoversAfterSlip != null ? leftoversAfterSlip.length : 0);

        // 3. Decompression bomb exceeding 64-byte cap blocked and temp dir cleaned up
        File bomb = new File(sdcard, "bomb.apkm");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(bomb))) {
            byte[] large = new byte[256];
            zos.putNextEntry(new ZipEntry("base.apk"));
            zos.write(large);
            zos.closeEntry();
        }
        List<OpalCastProtocol.ApkInstallStatusMsg> s3 = new ArrayList<>();
        assertFalse(installer.install(
                new OpalCastProtocol.ApkInstallRequestMsg(12, bomb.getAbsolutePath(), "apkm", true, false),
                s3::add));
        assertEquals("failed", s3.get(s3.size() - 1).stage);
        assertTrue(s3.get(s3.size() - 1).message.contains("size limit"));
        File[] leftoversAfterBomb = workRoot.listFiles();
        assertEquals(0, leftoversAfterBomb != null ? leftoversAfterBomb.length : 0);
    }

    @Test
    public void testCommitFailureAbandonsPmSession() throws Exception {
        File sdcard = tmpDir.newFolder("sdcard_fail");
        File workRoot = tmpDir.newFolder("work_fail");
        File apk = new File(sdcard, "bad.apk");
        Files.write(apk.toPath(), "APK_BYTES".getBytes(StandardCharsets.UTF_8));

        List<String> ops = new ArrayList<>();
        RemoteApkInstaller installer = new RemoteApkInstaller(
                Collections.singletonList(sdcard),
                workRoot,
                null,
                sdcard,
                (cmd, timeoutMs) -> {
                    ops.add(cmd[1]);
                    if ("install-create".equals(cmd[1])) {
                        return new ShellCommand.Result(0, "Success: created install session [55]\n", false);
                    }
                    if ("install-commit".equals(cmd[1])) {
                        return new ShellCommand.Result(1, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n", false);
                    }
                    return new ShellCommand.Result(0, "Success\n", false);
                },
                RemoteApkInstaller.MAX_TOTAL_EXTRACTED_BYTES);

        List<OpalCastProtocol.ApkInstallStatusMsg> statuses = new ArrayList<>();
        assertFalse(installer.install(
                new OpalCastProtocol.ApkInstallRequestMsg(20, apk.getAbsolutePath(), "apk", true, false),
                statuses::add));
        assertEquals(Arrays.asList("install-create", "install-write", "install-commit", "install-abandon"), ops);
        assertEquals("failed", statuses.get(statuses.size() - 1).stage);
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
