package com.setupbox.launcher;

/**
 * Finds a USB drive / SD card in the output of `df -k` (the kernel's numbers, so a box that fakes its size to the framework is not believed).
 * No Android classes: unit-tested. External volumes show up as /storage/<volume id> (Android 10 and 16) and as /mnt/media_rw/<volume id> (Android 10);
 * the internal storage is /storage/emulated (and /storage/self), which is never counted as external.
 */
final class StorageInfo {
    private StorageInfo() { }

    /** True for the mount points of a USB drive / SD card; false for the internal storage and everything else. */
    static boolean isExternalMount(String mount) {
        if (mount == null) return false;
        String name;
        if (mount.startsWith("/storage/")) name = mount.substring(9);
        else if (mount.startsWith("/mnt/media_rw/")) name = mount.substring(14);
        else return false;
        if (name.isEmpty() || name.indexOf('/') >= 0) return false;
        return !(name.equals("emulated") || name.equals("self") || name.equals("sdcard") || name.equals("sdcard0"));
    }

    /** {free GiB, total GiB} of the BIGGEST external volume in the output of `df -k`, or null when there is none. */
    static double[] largestExternal(String dfOutput) {
        if (dfOutput == null) return null;
        double[] best = null;
        for (String ln : dfOutput.split("\\r?\\n")) {
            String[] t = ln.trim().split("\\s+");
            if (t.length < 6 || !isExternalMount(t[t.length - 1])) continue;       // Filesystem 1K-blocks Used Available Use% Mounted-on
            try {
                double totalKb = Double.parseDouble(t[1]), availKb = Double.parseDouble(t[3]);
                if (!(totalKb > 0) || availKb < 0 || availKb > totalKb) continue;
                if (best == null || totalKb / 1048576.0 > best[1]) best = new double[]{availKb / 1048576.0, totalKb / 1048576.0};
            } catch (NumberFormatException ignored) { }
        }
        return best;
    }
}
