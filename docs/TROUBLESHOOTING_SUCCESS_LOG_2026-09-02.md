# Troubleshooting Success Log — 2026-09-02

This log preserves the difficult problems encountered during Bedtime Parental Control development, the actual root causes, the fixes that worked, the verification steps, and rollback/recovery notes. Keep this history even when the implementation changes later.

## 1. Remote BEDTIME commands were very slow even though polling looked healthy

**Problem / initial assumption**
- CHILD was polling around once per second.
- HTTP responses were fast and successful.
- It initially looked like Android polling, retries, or battery management was the main reason for 20–90 second command delays.

**Observed symptom**
- Parent BEDTIME ON/OFF writes succeeded, but CHILD sometimes kept seeing the old state for many seconds.
- Repeated taps sometimes appeared to make the command eventually take effect.

**Root cause**
- Workers KV is eventually consistent. A write could be accepted while another location still read an older value.

**Fix that worked**
- Replaced bedtime command/state storage with Cloudflare Durable Object `BedtimeState` via binding `BEDTIME_STATE_DO`.
- Public endpoint remained `/api/children/:childId/bedtime` so Android did not need a URL change.

**Verification**
- Wrangler deployment showed `env.BEDTIME_STATE_DO (BedtimeState) Durable Object`.
- Real-phone measurements improved to about 2 seconds BEDTIME ON and 3–4 seconds BEDTIME OFF.

**Do not regress**
- Do not move command state back to Workers KV unless eventual-consistency delays are intentionally accepted.

## 2. TECNO could not accept an update / old app could not be cleanly replaced

**Problem / initial assumption**
- It looked like Device Owner itself was making update/removal impossible.

**Observed symptom**
- Existing same-package app could not be updated because the signer no longer matched the new permanent release key.
- Device Owner state made removal difficult while the old signer was still installed.

**Root cause**
- Old TECNO build used a different/lost signing key. This was a signing-key mismatch, not a Device Owner bypass.

**Fix that worked**
- Factory-reset/reprovision TECNO once.
- Install the permanently signed APK.
- Use explicit user-0 install when normal install reported success but app visibility was wrong:
  `adb install --user 0 "C:\Users\DELL\Downloads\childapp-release.apk"`
- Then provision Device Owner:
  `adb shell dpm set-device-owner com.master.bedtime.child/.BedtimeDeviceAdminReceiver`

**Verification**
- Package became visible on user 0.
- Device Owner provisioning succeeded.
- Signed updates using the same permanent key later worked with `adb install -r`.

**Lesson**
- Preserve the permanent keystore forever. Never commit the keystore or passwords.

## 3. Backend URL typo caused online monitor failure

**Observed symptom**
- `UnknownHostException` and malformed backend such as duplicate `http://http://...`.

**Root cause**
- URL normalization was too permissive and saved malformed prefixes.

**Fix that worked**
- Normalize the backend URL, remove duplicate schemes, trim trailing slashes, and force Cloudflare `workers.dev` to HTTPS.

**Verification**
- CHILD resumed HTTP 200 polling against the correct Worker URL.

## 4. Monitor appeared configured but was not actually running

**Observed symptom**
- ADB/service diagnostics contained values such as `allowStartForeground=DENIED`, `startForegroundCount=0`, and `app=null`.
- Remote commands did nothing even though backend configuration looked correct.

**Root cause**
- The monitor process/service was absent. This was distinct from backend latency.
- Direct shell-start attempts were not a valid production start path because `BedtimeMonitorService` is correctly `exported=false`.

**Fix that worked**
- Reopen CHILD / use normal app-owned service start.
- Strengthen monitor persistence with `START_STICKY`, partial wake lock, foreground-service restart paths, boot/package-replaced recovery, watchdog behavior, and auto-start from configured CHILD UI.

**Verification**
- Healthy logs returned `HTTP 200`, state changes, and `deviceOwner=true`.

**Important test lesson**
- `adb shell am force-stop` intentionally puts the package into Android's stopped state. It cannot be used as proof that a production app should self-revive from force-stop.

## 5. Device Owner provisioning and strong bedtime lock

**Problem**
- Needed strong child protection without Accessibility/Usage Access and without breaking the physical power button.

**Working approach**
- Official managed-device path: Android Device Owner + Lock Task.
- Full-screen red BEDTIME MODE activity.
- Back/navigation blocked while active.
- Physical side power button still turns display off/on.
- Normal software Power Off/Restart menu is suppressed during managed Lock Task where Android/OEM supports it.

**Verification**
- Realme and TECNO both successfully reached Device Owner state during testing.
- Remote lock/unlock worked in strong mode.

**Boundary**
- Do not claim physical forced reboot can be blocked.

## 6. Normal uninstall needed to be blocked after CHILD setup

**Problem**
- A completed managed CHILD could still expose too much setup/admin surface and needed ordinary uninstall blocked.

**Fix that worked**
- Hide sensitive setup/admin controls after setup complete.
- Call `DevicePolicyManager.setUninstallBlocked(admin, packageName, true)` while Device Owner is active.

**Verification**
- Build #43: Android Settings -> Apps -> Bedtime showed Uninstall disabled/not pressable on tested device.

## 7. Strong anti-uninstall created a risk of locking ourselves out

**Problem**
- Once Device Owner + uninstall block worked, development needed a safe intentional parent/admin rollback path.

**Fix that worked**
- Added hidden Parent/Admin Recovery.
- Setup generates a 6-digit recovery code.
- Long-press protected CHILD status opens the recovery prompt.
- Correct code + confirmation stops monitor, clears bedtime state, removes uninstall block, releases Device Owner on tested phone, and clears pairing/recovery setup state.

**Verification**
- Build #44: hidden recovery prompt accepted the code.
- Device Owner was released.
- Settings Uninstall became available.
- App was successfully uninstalled.

**Caution**
- `clearDeviceOwnerApp()` is deprecated/device-dependent; it worked on the tested phone but should not be generalized to every OEM/API without verification.

## 8. Recovery code and pairing code intentionally differ

**Reason**
- Pairing code is temporary and only links CHILD to PARENT.
- Recovery code is the parent/admin emergency credential for local protected-device release.

**Security lesson**
- Someone who sees a temporary pairing code should not automatically gain Device Owner release capability.
- Recovery code belongs in the PARENT dashboard after secure pairing, not on an unauthenticated predictable Child ID endpoint.

## 9. Secure pairing controls did not appear on upgraded legacy CHILD

**Observed symptom**
- PARENT already showed `PAIR CHILD`, but CHILD showed no `GENERATE PAIRING CODE` button.

**Initial suspicion**
- It first looked like the wrong/old APK might be installed.

**Actual root cause**
- Upgraded CHILD preserved `setup_complete=true` from the older build.
- `refreshSetupState()` could detect missing `child_token`, but then the completed-state UI immediately hid pairing controls.

**Fix that worked**
- Add a legacy migration path for completed CHILD devices that have no secure child token.
- Preserve Device Owner and signed-update path; do not require uninstall/reprovision just to add pairing.

**Verification**
- Build #50 exposed the pairing flow on the upgraded CHILD.

## 10. First legacy-pairing migration fix exposed ALL old setup controls

**Observed symptom**
- Pairing button appeared, but backend, Child ID, test/release, and other setup controls also became visible.

**Root cause**
- Migration temporarily changed the device to a generic incomplete-setup UI state.

**Fix that worked**
- Add a locked-down legacy migration UI state.
- Show only the controls needed for migration: secure pairing / pairing code and Start Bedtime Monitor.
- Keep sensitive backend, Child ID, test, release, and unrelated setup controls hidden.

**Verification**
- Build #51 (`128f393`) succeeded.
- User verified the CHILD screen showed pairing flow and Start Bedtime Monitor only, instead of reopening the entire setup surface.

## 11. Parent/Child secure pairing flow became functional end-to-end

**Working behavior verified by user**
- CHILD can generate a pairing code.
- PARENT can pair using that code.
- PARENT receives/displays the separate 6-digit recovery code.
- CHILD can start the Bedtime monitor after pairing.
- Sensitive CHILD controls return to protected/hidden behavior after setup.
- Parent/Admin Recovery remains active as an emergency rollback mechanism.

## 12. Signed APK update vs uninstall behavior

**Working rule**
- Use `adb install -r` for normal development upgrades. It preserves app data/role and Device Owner when signer/package remain valid.
- Do not uninstall a managed CHILD just to upgrade pairing/UI code.

**Why this matters**
- Uninstall/release removes the management state and may require Device Owner provisioning again.

## 13. Realme/TECNO roles are not hard-coded to a model

**Confirmed design**
- The same APK can run as PARENT or CHILD.
- Role is selected by app state/setup, not phone model.
- Realme and TECNO can be swapped (for example TECNO CHILD, Realme PARENT) as long as the target CHILD receives Device Owner setup and secure pairing.

## 14. Current known-good backend deployment during secure pairing work

- Worker URL: `https://bedtime-parental-api.itjundobal.workers.dev`
- Binding confirmed: `BEDTIME_STATE_DO (BedtimeState)`.
- Pairing-era deployed Worker version recorded during testing: `beb18d2e-ab60-472d-8dbe-7ef22b6443f8`.
- Do not accidentally deploy stale `main` KV code over the feature branch Durable Object Worker.

## 15. Current verified checkpoint before multiple-child / billing work

Preserve these behaviors before adding new features:
- One signed APK with PARENT/CHILD role.
- Device Owner + Lock Task strong CHILD mode.
- Durable Object command state with fast remote ON/OFF.
- Service/boot/update recovery improvements.
- Completed CHILD anti-uninstall.
- Hidden Parent/Admin Recovery with separate recovery code.
- Secure pairing flow.
- Legacy completed CHILD can migrate to secure pairing without uninstall/reprovision.
- Legacy migration exposes only pairing + monitor-start controls, not all sensitive setup.
- Parent/Child pairing and Bedtime flow reported functional by user.

## Next planned phase

Multiple children -> per-child controls/recovery code -> Child #1 one-day free trial -> Child #2 paid gate -> monthly/yearly billing -> QR payment flow.

Rule for future troubleshooting entries: always record **problem / initial assumption -> symptom/logs -> root cause -> fix -> verification -> rollback or non-regression rule**.
