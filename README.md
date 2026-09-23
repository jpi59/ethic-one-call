# Ethic One Call

Ethic One Call helps a phone owner prepare a temporary, single-call handoff.

The owner authenticates with biometrics or PIN, then the phone enters a secure
kiosk mode with a locked dialpad. The guest can only dial and make one phone
call. During the call, a full-screen sandbox overlay prevents access to the
native dialer, contacts, or any other app. When the phone is returned, the
owner authenticates again to exit and reclaim full control.

## Privacy

- No contacts, call-log, SMS, or file access.
- No network permission — fully offline.
- No tracking, no analytics, no third-party services.
- Biometric or PIN authentication required to enter and exit lending mode.

## Permissions

| Permission | Purpose |
|:---|:---|
| `CALL_PHONE` | Place the call on behalf of the guest |
| `ANSWER_PHONE_CALLS` | End an active call programmatically |
| `READ_PHONE_STATE` | Detect when the call ends to return to the dialpad |
| `MODIFY_AUDIO_SETTINGS` | Set earpiece volume to maximum during a call |
| `USE_BIOMETRIC` | Owner authentication to enter and exit lending mode |
| `WAKE_LOCK` | Keep the proximity sensor active during a call |
| `SYSTEM_ALERT_WINDOW` | Display the in-call sandbox overlay on top of the native dialer |

## Building

```bash
./gradlew assembleRelease
```

The icon in this repository is an original vector created for this project,
with no third-party image asset. This statement describes the source of the
asset; it is not a legal guarantee that no unrelated patent exists anywhere.

Licensed under GPL-3.0-or-later. The project is submitted to F-Droid
for review; submission does not imply acceptance or publication.
