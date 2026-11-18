"""PostToolUse hook (Write|Edit): when a file that can change what the user sees or does is edited, remind Claude to update usermanual.md.

Reads the hook's JSON from stdin. Prints a JSON reply with additionalContext, once per session (a marker file in the temp folder), and only for
the app / launcher / phone-page sources, never for tests, docs or usermanual.md itself. Never fails the tool call: any problem -> no output, exit 0.
"""
import json, os, re, sys, tempfile

WATCH = [
    r"karaoke/app/src/main/java/.*\.java$",
    r"karaoke/app/src/main/assets/.*\.(html|js|css)$",
    r"karaoke/app/src/main/AndroidManifest\.xml$",
    r"karaoke/app/src/main/res/.*\.xml$",
    r"launcher/app/src/main/java/.*\.java$",
    r"launcher/app/src/main/assets/.*",
    r"tools/scripts/home_launcher\.py$",
]


def main():
    try:
        data = json.load(sys.stdin)
        path = (data.get("tool_input") or {}).get("file_path") or (data.get("tool_response") or {}).get("filePath") or ""
        path = path.replace("\\", "/")
        if not any(re.search(p, path) for p in WATCH):
            return
        marker = os.path.join(tempfile.gettempdir(), "claude_manual_reminder_" + re.sub(r"[^A-Za-z0-9_-]", "", str(data.get("session_id", "x"))))
        if os.path.exists(marker):
            return
        open(marker, "w").close()
        msg = ("You changed app/launcher source (" + os.path.basename(path) + "). If this changes anything the USER sees or does "
               "(remote keys, screens, menus, settings, storage/USB behaviour, phone page, launcher), update usermanual.md in the same piece of work "
               "(project rule 6 in CLAUDE.md). Skip it only for purely internal changes, and say so.")
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": msg}}))
    except Exception:
        return


if __name__ == "__main__":
    main()
