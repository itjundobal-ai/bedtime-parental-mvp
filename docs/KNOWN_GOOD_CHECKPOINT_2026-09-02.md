# Known-good checkpoint — 2026-09-02

This checkpoint documents the working bedtime-control setup before starting the single-APK PARENT/CHILD redesign, plus the major reliability findings and fixes confirmed during same-day testing.

## Repository / build
- Repository: `itjundobal-ai/bedtime-parental-mvp`
- Android package: `com.master.bedtime.child`
- Signed release build is produced by GitHub Actions.
- Permanent release keystore is NOT committed to the repository; signing secrets remain in GitHub Actions secrets.

## Cloudflare backend
- Worker URL: `https://bedtime-parental-api.itjundobal.workers.dev`
- Child state endpoint: `/api/children/:childId/bedtime`
- GET returns bedtime state.
- POST updates `{active, allowPowerControls, updatedAt}`.
- Parent key is currently optional for testing; final production flow still needs secure parent authentication/pairing.

### Important backend latency fix — KV to Durable Object
The first online implementation stored bedtime state in Workers KV using `BEDTIME_STATE`.

Observed symptom during real-phone testing:
- Child monitor was healthy and polling approximately once per second.
- HTTP 200 responses were commonly returned within tens of milliseconds.
- Despite that, remote ON/OFF could still take roughly 20–30+ seconds before the child saw the new state.
- Repeated Parent taps sometimes appeared to make the command eventually take effect.

Root cause / changed assumption:
- The delay was not primarily caused by the child poll interval or the Parent retry loop.
- Workers KV is eventually consistent across locations, so a write can be accepted while another location temporarily continues reading the older value.
- This matched the logs: the child could continuously receive fast HTTP 200 responses while still seeing the previous `active` state.

Fix:
- Replaced the bedtime command/state storage with a Cloudflare Durable Object.
- New Worker binding: `BEDTIME_STATE_DO` using Durable Object class `BedtimeState`.
- The public Worker URL and `/api/children/:childId/bedtime` endpoint remained unchanged, so the already-installed Android APK did not need a backend URL change.
- Deployment confirmed Wrangler binding output:
  `env.BEDTIME_STATE_DO (BedtimeState) Durable Object`
- Current pairing-era deployment verified with Worker version `beb18d2e-ab60-472d-8dbe-7ef22b6443f8`.

Measured result after Durable Object deployment:
- BEDTIME ON: approximately **2 seconds**.
- BEDTIME OFF: approximately **3–4 seconds**.
- This is the current acceptable working baseline and a major improvement over the previous 20–30+ second delays.

Do not revert bedtime command state back to Workers KV unless the consistency tradeoff is intentionally accepted.

## Android child behavior
- Device Owner is the preferred/strong mode.
- Managed Bedtime uses Lock Task and a full-screen red `BEDTIME MODE` activity.
- Back/navigation is blocked while Bedtime is active.
- Physical power button remains available for screen off/on.
- Normal on-screen global power/restart controls are suppressed in managed Lock Task mode where supported.
- After reboot, the monitor/boot flow should restore Bedtime if the backend state is still active.
- Foreground monitor service polls the backend.
- Poll interval was reduced to 1 second.
- Backend URL is normalized; Cloudflare `workers.dev` URLs are forced to HTTPS and duplicate `http://http://`-style prefixes are cleaned.
- Battery optimization/background restrictions must be disabled or set to unrestricted on tested phones for reliable remote response while unplugged.

## Monitor/service reliability findings
During testing, an important distinction was found between network latency and the child monitor process being absent.

A stale/failed ADB service-start record showed fields including:
- `recentCallingPackage=com.android.shell`
- `allowStartForeground=DENIED`
- `startForegroundCount=0`
- `app=null`

This was not a healthy running monitor. Direct shell start failed because `BedtimeMonitorService` is correctly `exported=false`; it should remain non-exported.

After cleaning the stale state and reopening CHILD mode, manually starting/restarting the monitor restored operation. This confirmed that some earlier failures were caused by the monitor being absent, not just backend delay.

Persistence improvements added on the feature branch include:
- `START_STICKY` foreground monitor behavior.
- Partial wake lock with `WAKE_LOCK` permission.
- Poll-gap diagnostics.
- Auto-start of a configured CHILD monitor when CHILD UI returns.
- `BOOT_COMPLETED` handling.
- `MY_PACKAGE_REPLACED` handling so signed APK updates can request monitor restart.
- Boot/custom restart receiver path for monitor self-recovery.
- Battery/background settings shortcut in CHILD setup.

Important test rule:
- Do not use Android Settings Force Stop or `adb shell am force-stop` as the normal production persistence test. A force-stopped package is intentionally placed into a stopped state and may not self-revive until user launch.
- Normal Home/back/task swipe behavior should be tested separately from explicit Force Stop.

## Important tested commands
Set Device Owner after clean provisioning / factory reset and before re-adding accounts:

```powershell
adb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver
```

Verify owner:

```powershell
adb shell dpm list-owners
```

Install/update signed APK:

```powershell
adb install -r "C:\Users\DELL\Downloads\childapp-release.apk"
```

If a fresh TECNO install reports Success but package is not visible, explicit user-0 install worked:

```powershell
adb install --user 0 "C:\Users\DELL\Downloads\childapp-release.apk"
adb shell pm path com.master.bedtime.child
```

Diagnostic monitor logs:

```powershell
adb logcat -c
adb logcat -s BedtimeMonitor
```

Monitor + boot/recovery diagnostics:

```powershell
adb logcat -c
adb logcat -s BedtimeBoot BedtimeMonitor
```

Healthy online polling shows:
- `HTTP 200`
- `State active=true/false`
- `deviceOwner=true`

## Tested devices / child IDs
- Realme: Device Owner successfully provisioned and online lock/unlock tested.
- TECNO: after factory reset, latest signed APK installed to user 0 and Device Owner successfully provisioned.
- Use distinct Child IDs per phone so one dashboard command does not control both unintentionally.

## Known problems already found and fixed
1. Old TECNO install used a different/lost signing key; factory reset/reprovision was required. This was a signing-key mismatch, not a Device Owner bypass.
2. Backend typo `http://http://...` caused `UnknownHostException`; URL normalization was added.
3. Polling originally used 3 seconds; changed to 1 second.
4. Unlock/lock state transitions were adjusted so commands are not needlessly relaunched every poll and unlock is sent even if Android reports Lock Task already off.
5. Phones may delay remote reaction if Android battery/background management suspends the monitor; set the Bedtime app to Unrestricted / No restrictions / Allow background activity.
6. A monitor service can be absent even though configuration is correct. The service/recovery path was strengthened with boot/package-replaced/foreground-return auto-start behavior and watchdog logic.
7. Workers KV produced real-world command visibility delays even while the child was polling quickly. Bedtime state was moved to a Durable Object; measured response improved to about 2 seconds ON and 3–4 seconds OFF.

## Single-APK PARENT / CHILD branch progress
Development branch: `feature/single-apk-parent-child`.

Implemented / tested:
- First-open role selector with `PARENT` and `CHILD`.
- PARENT native dashboard.
- Secure Pair Child flow added.
- CHILD generates a pairing code; PARENT enters it to bind the child.
- CHILD receives a secure child credential for authenticated monitor traffic.
- Parent dashboard stores/shows the child recovery code after pairing.
- Child monitor recovery/persistence work continued on the same branch.
- Durable Object backend migration completed and deployed successfully.

Role behavior:
- `adb install -r` is an update and preserves app data/selected role and, with the permanent signer, preserves Device Owner state.
- Full uninstall normally clears app data and removes Device Owner only after intentional managed release; do not uninstall during normal upgrade testing.

## Verified hardening and rollback history
### Ordinary uninstall block
Problem / requirement:
- A completed CHILD device must not be casually uninstalled from Android Settings.

Fix:
- Completed managed CHILD calls Device Policy Manager `setUninstallBlocked(..., true)`.
- Sensitive setup/test controls are hidden after setup completion.

Verification:
- On real device, Android Settings → Apps → Bedtime showed Uninstall disabled/not pressable.
- This is a verified success.

### Parent/Admin recovery release
Problem / requirement:
- Strong Device Owner protection must have an intentional parent/admin recovery path so the device is not permanently trapped during testing or emergency maintenance.

Fix:
- Hidden recovery entry is exposed by long-pressing the protected status area.
- CHILD asks for a 6-digit Parent/Admin recovery code.
- Correct code can stop the monitor, clear active bedtime state, remove uninstall block, and release Device Owner on the tested device.

Verification:
- Recovery prompt accepted the correct code.
- Device Owner was released.
- Android Settings then enabled Uninstall.
- App was successfully uninstalled.
- This full rollback path is verified on the tested phone.

Important limitation:
- `clearDeviceOwnerApp()` behavior can vary by Android/OEM/API. It worked on the tested phone; do not assume identical behavior on every OEM without testing.

## Pairing and recovery code behavior
- Pairing code and recovery code are intentionally different.
- Pairing code is for binding a CHILD to the PARENT app.
- Recovery code is the persistent Parent/Admin emergency credential shown in the Parent dashboard.
- The recovery code is the code typed into the CHILD hidden Parent/Admin Recovery prompt.
- A pairing code must not be treated as an admin-release credential.

## Legacy CHILD upgrade migration — troubleshooting ledger
### Problem 1: new pairing button did not appear on an already-completed CHILD
Symptom:
- New APK was installed with `adb install -r`.
- PARENT already showed Pair Child, but CHILD showed the old completed protected UI and no Generate Pairing Code button.

Initial suspicion:
- An old APK may have been installed from Downloads.

Root cause after code inspection:
- The upgrade preserved `setup_complete=true` from the older CHILD build.
- `refreshSetupState()` correctly detected that no child token existed, but the later `if (setupComplete)` branch immediately called the hardened completed UI and hid the pairing controls.

Fix:
- Added legacy completed-setup migration logic for devices that are complete but have no secure child token.
- Build #50 / commit `657a894` introduced the migration path.

Verification:
- After updating, Generate Pairing Code appeared on the existing managed CHILD without uninstalling or reprovisioning Device Owner.

### Problem 2: migration exposed all setup/test controls
Symptom:
- After Build #50, pairing became available but the CHILD also exposed backend, Child ID, test/release and other setup controls.

Root cause:
- The first migration implementation temporarily changed the old completed device to a normal incomplete setup state, so the general setup UI became visible.

Fix:
- Added a dedicated locked-down legacy pairing migration UI.
- Only pairing-related controls and required monitor continuation controls are exposed.
- Backend, Child ID, test, Device Owner release, and other sensitive setup controls remain hidden.
- Build #51 / commit `128f393` (`Keep legacy child migration locked down during pairing`) completed successfully.

Verification:
- Real-device update showed only the expected pairing flow and Start Bedtime Monitor control.
- User confirmed the flow is functional.
- After pairing/monitor completion, the CHILD returns to the protected hidden-control state.

## Current verified functional flow
As of the Build #51 checkpoint, the following has been confirmed working in real-device testing:
- One signed APK supports PARENT and CHILD roles.
- Device Owner strong mode works.
- Remote Bedtime ON/OFF works with Durable Object latency around a few seconds.
- Full red Lock Task bedtime screen works.
- Physical side power button still turns the display off/on.
- Ordinary app uninstall is blocked on a completed managed CHILD.
- Hidden Parent/Admin Recovery remains active.
- Correct Parent recovery code can release the tested CHILD for maintenance/uninstall.
- Secure pairing works: CHILD generates pairing code, PARENT pairs the child, and Parent displays the separate 6-digit recovery code.
- Existing legacy CHILD installs can be upgraded with `adb install -r` without losing Device Owner.
- Legacy migration now exposes only the minimum pairing controls instead of reopening all sensitive setup controls.
- User confirmed the pairing / monitoring flow is functional end-to-end.

## Troubleshooting principles to preserve
For every future issue, keep this sequence in the report:
1. Original assumption / expected behavior.
2. Actual symptom and logs.
3. Root cause found.
4. Exact fix applied.
5. Real-device verification result.
6. Rollback or recovery procedure if applicable.

Do not remove failed attempts from the history when they helped identify the real cause. The goal is to preserve the difficult lessons so the same problem does not have to be rediscovered.

## Current baseline / next milestone
Current protected baseline:
- Durable Object command path must remain.
- Permanent signing pipeline must remain.
- Device Owner + Lock Task behavior must remain.
- Anti-uninstall and hidden Parent/Admin Recovery must remain.
- Secure Pair Child + separate Parent recovery code must remain.
- Legacy upgrade migration must remain locked down.

Next milestone:
- Multiple children / Add Child dashboard.
- Per-child Bedtime control and recovery-code display.
- Child #1 one-day free trial.
- Child #2 and additional children payment gating.
- Trial expiry / fail-safe OFF rules.
- Later QR payment flow.
