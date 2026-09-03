# ONE APK — Parent + Child Structure

## Goal

Build one Android APK for both Parent and Child phones.

The first screen is always **Choose Role**. The same installer is used on both devices.

```text
OPEN ONE APK
    |
    v
CHOOSE ROLE
    |
    +-------------------+
    |                   |
    v                   v
 PARENT               CHILD
    |                   |
    v                   v
Parent setup          Child setup
Parent dashboard      Device Owner
Add Child             Monitor
Pair child            Battery/background
Bedtime ON/OFF        Child Active
Remote control        Pairing code
```

## First-open flow

1. Install the same `app-release.apk` on either phone.
2. Open the app.
3. Show **PARENT** and **CHILD** role buttons.
4. Save the selected role locally.
5. Open the role-specific screen.

The role chooser must not create a second APK or a separate installer.

## CHILD flow — exact order

The Child flow must preserve the known-good Device Owner/monitor implementation.

```text
CHILD selected
    |
    v
SETUP
    |
    v
ACCOUNT / SECURITY
    |
    v
REMOVE SAVED ACCOUNTS
    |
    v
CONTINUE SETUP
    |
    v
DEVICE OWNER
    |
    v
START BEDTIME MONITOR
    |
    v
BATTERY / BACKGROUND
    |   No restrictions / Unrestricted
    |   Auto-run / Background = Allow
    v
SAVE & RESTART
    |
    v
CHILD ACTIVE ✓
    |
    v
PAIRING SECTION APPEARS
    |
    v
GENERATE / DISPLAY 6-DIGIT PAIRING CODE
    |
    v
ENTER CODE IN PARENT APP
    |
    v
REMOTE BEDTIME MONITORING
```

### Important Child rules

- **Pairing must NOT block the Child setup.**
- Pairing UI must stay hidden until `setup_complete=true`.
- Device Owner must be active before managed Child protection is considered ready.
- Battery/background setup must be completed before the monitor is started/restarted.
- `CHILD ACTIVE ✓` is the completion state of local Child provisioning.
- After `CHILD ACTIVE ✓`, pairing becomes available.
- Existing Device Owner, Lock Task, monitor, boot restore, backend URL normalization, and diagnostic logging must not be rewritten from scratch.
- A completed Child setup without a `child_token` must not be forcibly migrated back into an incomplete setup state. It should remain active and expose pairing recovery/generation.

## PARENT flow

```text
PARENT selected
    |
    v
PARENT ACCOUNT / SETUP
    |
    v
PARENT DASHBOARD
    |
    v
ADD CHILD
    |
    v
ENTER CHILD 6-DIGIT PAIRING CODE
    |
    v
CHILD PAIRED ✓
    |
    v
SELECT CHILD
    |
    +-----------------------+
    |                       |
    v                       v
BEDTIME ON              BEDTIME OFF
    |                       |
    +-----------+-----------+
                v
        VERIFY REMOTE STATE
```

## Android source structure

```text
android/childapp/
├── src/main/java/com/master/bedtime/child/
│   ├── RoleSelectionActivity.java   # ONE APK entry point / role chooser
│   ├── ParentActivity.java          # Parent setup, pairing, remote controls
│   ├── MainActivity.java            # Child setup and Child UI
│   ├── BedtimeDeviceAdminReceiver.java
│   ├── BedtimeMonitorService.java
│   ├── BedtimeLockActivity.java
│   ├── BedtimeOverlay.java
│   └── BootReceiver.java
│
├── src/main/res/layout/
│   ├── activity_role_selection.xml  # Parent / Child first screen
│   ├── activity_parent.xml          # Parent dashboard
│   ├── activity_main.xml            # Child setup / active screen
│   └── overlay_bedtime.xml
│
└── src/main/AndroidManifest.xml     # RoleSelectionActivity is launcher
```

## State model

### Role

`app_role.role`

- `parent`
- `child`

### Child local state

`cfg`

- `accounts_confirmed`
- `battery_settings_confirmed`
- `setup_complete`
- `backend`
- `child`
- `child_token`
- `pair_code`
- `parent_recovery_pin`

### Parent local state

`parent_cfg`

- `child`
- `parent_token`
- `recovery_pin`

## Backend pairing

Child creates a pairing session after local setup is complete:

`POST /api/pairing/start`

Parent claims the displayed 6-digit code:

`POST /api/pairing/claim`

Remote bedtime state remains:

`GET /api/children/:childId/bedtime`

`POST /api/children/:childId/bedtime`

## Safety / regression rules

1. Keep `backup/known-good-online-lock-2026-09-02` untouched.
2. Do not change the permanent Android signing key.
3. Do not rewrite the Device Owner/Lock Task engine from zero.
4. Build the ONE APK from `feature/single-apk-parent-child`.
5. Test the same APK separately as Parent and Child.
6. On the TECNO Child test, verify package path and Device Owner with ADB before judging the APK.
7. Test Child setup, reboot restore, battery/background behavior, pairing, Parent BEDTIME ON, and Parent BEDTIME OFF before merging.

## Current known-good reference

The original Child setup/monitor implementation is preserved in:

`backup/known-good-online-lock-2026-09-02`

The current ONE APK branch already contains the role chooser and Parent/Child activities; this document defines the target flow that the implementation must follow.
