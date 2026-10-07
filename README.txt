A11y Inspector popup diagnostics — source patch, NOT an APK

Base repository: https://github.com/KunukuntlaGowtham/dummy
Base commit: faf21852b7ebf3763fdad4dd1aa6745372094eed (Inspector build 49)
Branch for this update: codex/inspector-popup-diagnostics

STATUS
Changes prepared locally. GitHub rejected create-tree with HTTP 403:
Resource not accessible by integration. No remote branch, commit, or build was created.
Java syntax parsing passed. Android compilation, Gradle unit tests and device testing
have NOT run. The current popup's appearance remains unverified.

CHANGES
- Deep scans save a PNG in Download/A11yInspector and identify its filename in the report.
- Popup diagnostics list accessible targets and possible empty backdrop rectangles.
- Watch popup 15 s records tree changes at 500 ms intervals and captures the final screen.
- Auto-clear pauses during scans/watches; overlays hide briefly for screenshot capture.
- Missing accessibility nodes are no longer reported as proof that no popup exists.
- Clear waits for screenshot cooldowns and performs multiple observations.
- Screenshot verification failures no longer produce duplicate continuations or false visual success.
- Tick stops when a new popup hint remains unresolved.
- Three unit tests cover change-report generation. Workflow runs unit tests before APK build.

APPLY (from a clean clone)
git switch -c codex/inspector-popup-diagnostics faf21852b7ebf3763fdad4dd1aa6745372094eed
git apply --check /path/to/changes.patch
git apply /path/to/changes.patch
./gradlew :inspector:testDebugUnitTest :inspector:assembleDebug -PversionCode=50

Use a versionCode higher than your installed version. GitHub Actions supplies its own
run number. Alternatively copy the replacement files in files/ into the base checkout.
Review and commit the changes, then push the named branch. The updated inspector workflow
builds that exact branch and publishes its APK through the existing release job.

CAPTURE ON PHONE (after successfully building and installing)
1. Open the target page before its popup appears.
2. Long-press Scan. In its result card, tap Watch popup 15 s.
3. Trigger the popup yourself and leave it open until capture completes.
4. Save/share the full text report and attach the PNG from Downloads/A11yInspector.
For a popup already open, long-press Scan to capture that state immediately.
Clear still uses accessible targets or the existing purple-button detector; other
colours/designs need examination of the new screenshot. No OCR/DOM extraction is added.
Screenshots stay on the phone unless you share them. The supplied personal scan is not
included in this source package.
