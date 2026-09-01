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

## TECNO Device Owner dead-end and recovery
### Problem / initial assumption
The original TECNO had an older Bedtime build installed as Device Owner. That build was signed with a different key that was no longer available. Because Device Owner is a protected admin state, replacing that package with the new permanently signed build was not possible by normal update.

### Symptoms / logs
- `adb install -r ...` failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because the installed package signature did not match the new release key.
- `adb shell dpm remove-active-admin --user 0 com.master.bedtime.child/.BedtimeDeviceAdminReceiver` failed with a `SecurityException` saying it was an attempt to remove a non-test admin.
- Therefore the old Device Owner package could not be cleanly replaced or removed from outside the app.

### Root cause
The old Device Owner app was signed with a lost/different signing key and did not have a usable in-app self-release path signed by that same old key. Android correctly blocked both signature replacement and external removal of that protected Device Owner state.

### Recovery that worked
1. Factory reset / reprovision the TECNO so the stale Device Owner state and old package were removed.
2. Enable USB debugging again after setup.
3. Install the current permanently signed APK.
4. When `adb install -r` reported Success but the package was not visible to `pm path`, explicitly install to the owner user:

```powershell
adb install --user 0 "C:\Users\DELL\Downloads\childapp-release.apk"
adb shell pm path com.master.bedtime.child
```

5. After `pm path` confirmed the package, set Device Owner again:

```powershell
adb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver
```

6. Verify the command returns `Success: Device owner set to package ...` and `Active admin set to component ...`.

### Important lesson
For the old TECNO state, factory reset was genuinely required because the signing key needed to update/self-release the old Device Owner package was unavailable. The later success did not bypass Android's Device Owner protection; it came from clean reprovisioning with the permanent signing key and then setting Device Owner again correctly.

### Prevention for future builds
- Keep using the same permanent release signing key.
- Never lose or rotate the signing key for an installed Device Owner build without a planned migration path.
- Keep a tested in-app Device Owner release/reprovision mechanism in development/test builds so future test devices can be released before changing package/signing setup.
- Record the exact installed signer/build when provisioning test devices.

## Important breakthrough — strong forced Bedtime lock
### Initial assumption
At an earlier stage, a true parent-controlled forced lock looked impractical because ordinary Android overlays/activities can usually be escaped with Back, Recents, Home, system UI, or other navigation, and Android does not allow normal apps to intercept every system control.

### What changed the answer
The workable solution was not a stronger overlay. The solution was to provision the child app as **Device Owner** and use Android **Lock Task / managed kiosk mode** for the Bedtime activity.

### Proven solution
- Provision the Bedtime app as Device Owner.
- Allow the package for Lock Task with `DevicePolicyManager.setLockTaskPackages(...)`.
- Start `BedtimeLockActivity` and call `startLockTask()` when Bedtime becomes active.
- Keep Back disabled and hide status/navigation system UI while managed Bedtime is active.
- Use `LOCK_TASK_FEATURE_NONE` where supported so the normal on-screen global power/restart menu is suppressed during Bedtime.
- The physical power button still remains usable for screen off/on; physical forced reboot cannot be reliably blocked by an Android app.
- If the device reboots while the backend still reports Bedtime active, the boot/monitor flow should restore the managed lock.

### Verification
This strong managed lock was tested successfully on the child phone: ordinary Back/Recents/swipe navigation did not dismiss Bedtime, and remote parent commands could later unlock it.

### Lesson for future troubleshooting
Do not stop at an early conclusion of "not possible" when the limitation only applies to a normal app permission level. Re-check whether Android has an officially supported managed-device capability such as Device Owner, Device Policy Manager, or Lock Task that changes what is possible. Document the exact limitation and the elevated-management solution separately.

## Troubleshooting record format going forward
For every meaningful issue, record:
- **Problem / initial assumption** — what appeared impossible or broken.
- **Symptom / logs** — exact behavior or error message.
- **Root cause** — what actually caused it.
- **Fix** — exact code change, setting, or command.
- **Verification** — how the fix was proven on a real device.
- **Rollback point** — branch/commit to return to if a later change breaks it.

## Next planned milestone
Build ONE Android APK with a first-launch role selector:
- `PARENT` mode: native in-app dashboard, child/device selection, Bedtime ON/OFF.
- `CHILD` mode: current Device Owner setup, pairing/Child ID, foreground monitor, managed Bedtime lock.

Do not remove the working child flow while adding parent mode. Develop the new role-selector/dashboard in a separate branch first, then merge only after testing.
