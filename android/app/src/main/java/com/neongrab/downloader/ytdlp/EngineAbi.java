package com.neongrab.downloader.ytdlp;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.RandomAccessFile;

/** Device ABI vs lean engine pack download. */
final class EngineAbi {

    private static final int EM_X86_64 = 62;
    private static final int EM_AARCH64 = 183;

    private EngineAbi() {}

    /** First ABI in the list is what the process actually runs (emulators may list others). */
    static String primaryAbi() {
        return Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0]
                : "unknown";
    }

    static boolean deviceUsesArm64() {
        return "arm64-v8a".equals(primaryAbi());
    }

    static boolean deviceUsesX86_64() {
        return "x86_64".equals(primaryAbi());
    }

    static boolean isSupported(Context context) {
        if (EngineNativeLoader.apkBundledNativesPresent(context)) return true;
        return deviceUsesArm64() || deviceUsesX86_64();
    }

    static void requireSupportedDevice(Context context) throws Exception {
        if (isSupported(context)) return;
        throw new Exception(unsupportedMessage());
    }

    static String unsupportedMessage() {
        return "Unsupported device CPU ("
                + primaryAbi()
                + "). NeonGrab needs arm64-v8a or x86_64 (64-bit phone or emulator).";
    }

    /** True when native libs in {@code binDir} match this device's CPU. */
    static boolean validateEngineBinDir(File binDir) {
        if (binDir == null || !binDir.isDirectory()) return false;
        if (!EngineNativeLoader.verifyBinDir(binDir)) return false;
        return libMatchesDevice(new File(binDir, "libpython.so"));
    }

    static boolean libMatchesDevice(File lib) {
        if (lib == null || !lib.isFile() || lib.length() < 20) return false;
        try (RandomAccessFile raf = new RandomAccessFile(lib, "r")) {
            byte[] magic = new byte[4];
            if (raf.read(magic) != 4) return false;
            if (magic[0] != 0x7f || magic[1] != 'E' || magic[2] != 'L' || magic[3] != 'F') {
                return false;
            }
            raf.seek(18);
            int lo = raf.readUnsignedByte();
            int hi = raf.readUnsignedByte();
            int machine = lo | (hi << 8);
            if (deviceUsesArm64()) return machine == EM_AARCH64;
            if (deviceUsesX86_64()) return machine == EM_X86_64;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isAbiMismatchMessage(String raw) {
        if (raw == null) return false;
        return raw.contains("EM_AARCH64")
                && (raw.contains("EM_X86_64") || raw.contains("EM_X86"));
    }

    static String friendlyError(String raw) {
        if (raw == null) return null;
        if (isAbiMismatchMessage(raw)) {
            return "Download engine did not match this device. NeonGrab is reinstalling it — "
                    + "wait for setup to finish, then try again.";
        }
        return raw;
    }

    static String wrongPackMessage() {
        return "Engine pack does not match this phone ("
                + primaryAbi()
                + "). Connect to Wi‑Fi and tap Retry to download the correct pack.";
    }
}
