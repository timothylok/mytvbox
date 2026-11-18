package com.setupbox.launcher;

/**
 * Plain-Java checks for the USB-drive detection of the launcher's storage panel. Run from the launcher folder:
 *   javac -encoding UTF-8 -d build/qtest app/src/main/java/com/setupbox/launcher/StorageInfo.java tests/StorageInfoTest.java
 *   java -cp build/qtest com.setupbox.launcher.StorageInfoTest
 */
public class StorageInfoTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 0.05; }

    public static void main(String[] a) {
        String google = "Filesystem            1K-blocks      Used Available Use% Mounted on\n"
                + "/dev/block/dm-0         1572864   1572864         0 100% /\n"
                + "/dev/block/mmcblk0p14   3939744   1337988   2502676  35% /data/user/0\n"
                + "/dev/fuse               3939744   1337988   2502676  35% /storage/emulated\n"
                + "/dev/fuse             465437696  26400768 438756352   6% /storage/A6D0-F59E\n";
        double[] g = StorageInfo.largestExternal(google);
        check("Android 16 style: the USB volume is found with the right free and total size", g != null && near(g[1], 465437696 / 1048576.0) && near(g[0], 438756352 / 1048576.0));
        check("the internal /storage/emulated is not counted as external", StorageInfo.largestExternal("/dev/fuse 3939744 1337988 2502676 35% /storage/emulated\n") == null);

        String a10 = "Filesystem       1K-blocks     Used Available Use% Mounted on\n"
                + "/dev/block/vold/public:8,1 500000000 100000000 400000000 20% /mnt/media_rw/1234-ABCD\n"
                + "/dev/fuse        500000000 100000000 400000000  20% /storage/1234-ABCD\n"
                + "/data/media       25000000   5000000  20000000  20% /storage/emulated\n";
        double[] t = StorageInfo.largestExternal(a10);
        check("Android 10 style (two mounts of the same drive): found once, right size", t != null && near(t[1], 500000000 / 1048576.0));

        check("no USB drive: null", StorageInfo.largestExternal("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-0 1572864 1572864 0 100% /\n/dev/fuse 1 1 0 100% /storage/self\n") == null);

        String two = "/dev/fuse 100000000 1000000 99000000 1% /storage/AAAA-1111\n/dev/fuse 900000000 1000000 899000000 1% /storage/BBBB-2222\n";
        double[] b = StorageInfo.largestExternal(two);
        check("two drives: the biggest one is shown", b != null && near(b[1], 900000000 / 1048576.0));

        check("garbage, empty and null input never throw", StorageInfo.largestExternal("") == null && StorageInfo.largestExternal(null) == null
                && StorageInfo.largestExternal("hello world\n\n\u0000\n/storage/XXXX-0000") == null);
        check("a non-numeric size column is skipped", StorageInfo.largestExternal("/dev/fuse abc 1 2 3% /storage/CCCC-3333\n") == null);
        check("impossible numbers (available > total, zero total) are skipped",
                StorageInfo.largestExternal("/dev/fuse 100 50 200 10% /storage/DDDD-4444\n/dev/fuse 0 0 0 0% /storage/EEEE-5555\n") == null);
        check("a sub-folder mount or the storage root is not a volume", !StorageInfo.isExternalMount("/storage/emulated/0") && !StorageInfo.isExternalMount("/storage")
                && !StorageInfo.isExternalMount("/storage/") && !StorageInfo.isExternalMount("/data") && !StorageInfo.isExternalMount(null));
        check("known external names", StorageInfo.isExternalMount("/storage/A6D0-F59E") && StorageInfo.isExternalMount("/mnt/media_rw/A6D0-F59E")
                && StorageInfo.isExternalMount("/storage/usbotg"));
        check("windows line endings are fine", StorageInfo.largestExternal(google.replace("\n", "\r\n")) != null);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
