r"""
Switch which launcher is the home screen on the Google TV box (adb), without uninstalling anything.

    python tools\scripts\home_launcher.py ours   --device 192.168.3.97:2025    # our launcher (com.setupbox.launcher)
    python tools\scripts\home_launcher.py google --device 192.168.3.97:2025    # restore the Google TV launcher
    python tools\scripts\home_launcher.py status --device 192.168.3.97:2025

Why "ours" also disables the Google TV launcher: on this box the HOME key starts Google's home screen by its exact component name,
so setting our launcher as the default home app (role + preferred activity) is not enough. `pm disable-user` is reversible:
"google" enables it again and makes it the default home app. Nothing is uninstalled and no data is touched.
The wireless-debugging port changes after a reboot; see `adb mdns services` for the current one.
"""
import argparse, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = os.path.join(HERE, "..", "sdk", "platform-tools", "adb.exe")
OURS, OURS_ACT = "com.setupbox.launcher", "com.setupbox.launcher/.MainActivity"
GOOGLE, GOOGLE_ACT = "com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx/.home.HomeActivity"


def sh(dev, cmd):
    r = subprocess.run([ADB, "-s", dev, "shell", cmd], capture_output=True, timeout=60)
    return (r.stdout + r.stderr).decode("utf-8", "replace").strip()


def holder(dev):
    return sh(dev, "cmd role get-role-holders android.app.role.HOME")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("which", choices=["ours", "google", "status"])
    ap.add_argument("--device", required=True)
    a = ap.parse_args()
    subprocess.run([ADB, "connect", a.device], capture_output=True)
    if a.which == "ours":
        print(sh(a.device, "cmd role add-role-holder --user 0 android.app.role.HOME " + OURS))
        print(sh(a.device, "cmd package set-home-activity --user 0 " + OURS_ACT))
        print(sh(a.device, "pm disable-user --user 0 " + GOOGLE))
    elif a.which == "google":
        print(sh(a.device, "pm enable --user 0 " + GOOGLE))
        print(sh(a.device, "cmd role add-role-holder --user 0 android.app.role.HOME " + GOOGLE))
        print(sh(a.device, "cmd package set-home-activity --user 0 " + GOOGLE_ACT))
    print("home app is now:", holder(a.device))
    print("google launcher:", sh(a.device, "pm list packages -d | grep " + GOOGLE) or "enabled")
    return 0


if __name__ == "__main__":
    sys.exit(main())
