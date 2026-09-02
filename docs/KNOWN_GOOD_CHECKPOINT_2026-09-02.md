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
- Confirmed deployed Worker version during the test: `96fc5576-ca17-4f49-bdda-a627fc7905a1`.

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
- Use distinct Child IDs per phone (for example `child-001`, `child-002`) so one dashboard command does not control both unintentionally.

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
- PARENT native dashboard with Child ID, BEDTIME ON, BEDTIME OFF, and status refresh.
- Parent command retry + server-state verification.
- CHILD setup flow retained.
- Child monitor recovery/persistence work continued on the same branch.
- Durable Object backend migration completed on this branch and deployed successfully.

Role behavior:
- `adb install -r` is an update and preserves app data/selected role.
- Full uninstall normally clears app data, but strict "always choose role again after reinstall" should also account for Android backup/restore behavior before release.

## Current baseline / next milestone
Current confirmed baseline after the Durable Object migration:
- Strong Device Owner + Lock Task bedtime screen works.
- Remote backend is online.
- Child polling can run at about 1-second intervals.
- Remote response measured at roughly 2 seconds ON and 3–4 seconds OFF.
- This latency is acceptable for the current MVP and should be preserved as a rollback/reference point.

Next priorities should favor stability over unnecessary simultaneous changes:
- keep the Durable Object command path;
- continue verifying monitor persistence after normal task swipe, reboot, and signed APK update;
- verify distinct Child IDs on multiple phones;
- later add secure parent pairing/authentication and optional child-online/ack status.
