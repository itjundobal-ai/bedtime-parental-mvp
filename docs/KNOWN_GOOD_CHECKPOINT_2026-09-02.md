# Known-good checkpoint — 2026-09-02

This checkpoint documents the working bedtime-control setup before starting the single-APK PARENT/CHILD redesign.

## Repository / build
- Repository: `itjundobal-ai/bedtime-parental-mvp`
- Android package: `com.master.bedtime.child`
- Signed release build is produced by GitHub Actions.
- Permanent release keystore is NOT committed to the repository; signing secrets remain in GitHub Actions secrets.

## Cloudflare backend
- Worker URL: `https://bedtime-parental-api.itjundobal.workers.dev`
- KV namespace binding: `BEDTIME_STATE`
- Child state endpoint: `/api/children/:childId/bedtime`
- GET returns bedtime state.
- POST updates `{active, allowPowerControls, updatedAt}`.
- Parent key is currently optional for testing; final production flow still needs secure parent authentication/pairing.

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

Healthy online polling shows:
- `HTTP 200`
- `State active=true/false`
- `deviceOwner=true`

## Tested devices / child IDs
- Realme: Device Owner successfully provisioned and online lock/unlock tested.
- TECNO: after factory reset, latest signed APK installed to user 0 and Device Owner successfully provisioned.
- Use distinct Child IDs per phone (for example `child-001`, `child-002`) so one dashboard command does not control both unintentionally.

## Known problems already found and fixed
1. Old TECNO install used a different/lost signing key; factory reset/reprovision was required.
2. Backend typo `http://http://...` caused `UnknownHostException`; URL normalization was added.
3. Polling originally used 3 seconds; changed to 1 second.
4. Unlock/lock state transitions were adjusted so commands are not needlessly relaunched every poll and unlock is sent even if Android reports Lock Task already off.
5. Phones may delay remote reaction if Android battery/background management suspends the monitor; set the Bedtime app to Unrestricted / No restrictions / Allow background activity.

## Next planned milestone
Build ONE Android APK with a first-launch role selector:
- `PARENT` mode: native in-app dashboard, child/device selection, Bedtime ON/OFF.
- `CHILD` mode: current Device Owner setup, pairing/Child ID, foreground monitor, managed Bedtime lock.

Do not remove the working child flow while adding parent mode. Develop the new role-selector/dashboard in a separate branch first, then merge only after testing.
