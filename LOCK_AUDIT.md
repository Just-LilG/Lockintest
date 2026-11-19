# LockIn lock audit

Scope: partner lock (LockService, LocalLock, LockGuard, SettingsGuardService, BootGate), protection windows, and the Supabase SQL in the repo.
Not compiled or run: no Android SDK or network here. Every change below needs a device test.

## Fixed in the patch (lockin-hardening.patch)

| # | Loophole | Severity | Fix |
|---|---|---|---|
| 1 | `lock_emergency()` still callable on the server. Anyone locked can POST it with their own token and end the lock instantly. The UI button is gone (`onEmergency` is wired to nothing), the RPC is not. | Critical | `20261128_lock_hardening.sql` makes it raise. Apply it to the live project. |
| 2 | Emergency-call pause was a flat 45 s with the cover off, no matter what was on screen. Tap "Emergency call", open any app, use it for 45 s, tap again when the cover returns. Partner was told once per lock. | High | Pause (and the in-call pause) now ends the moment another app is on screen. Each dialer use is reported, up to 5 per lock. |
| 3 | Cover is an overlay window and Android can hide it (Android 12+ lets any app ask to hide overlays; some system and OEM screens do too). The service still believed the cover was up, so that app stayed usable and no escape was counted. Only Settings screens were handled. | High | If any non-allowed app stays on screen for 2.5 s while the cover should be up, send Home. Also stops audio/video running behind the cover. Reads both the usage log and the guard's window list, so switching off one does not blind it. |
| 4 | "Stopped" and "emergency dial" warnings were once per lock. Force-stop LockIn a second time and nobody hears. | Medium | Repeatable up to 5 per lock. Queue now removes one copy per send so duplicates aren't lost. |
| 5 | Device-owner phones: Force stop and Clear storage on LockIn were still open during a lock, as was switching to another user or guest. | High (Total level) | `DISALLOW_APPS_CONTROL`, `no_user_switch`, and `setUserControlDisabledPackages` on Android 11+, all lifted when the lock ends. |
| 6 | Guard ignored OEM battery and "device care" screens where an app can be put to sleep or force-stopped (Samsung Device care, Oppo/Realme, Vivo, Xiaomi). | Medium | More package names watched. |
| 7 | New: call UI packages (`incallui`, `phone`, `telecom`, `emergency`, common dialers) counted as part of the phone app, so tightening #2 cannot cover a real call. | Safety | Added to the allowed-beside-LockIn set. |

## Found, not changed (needs your call)

1. **Non-device-owner phones can still be beaten by force-stop, Clear data, or safe mode + uninstall.** The accessibility guard is the only barrier and it dies with a force-stop. Today the partner gets one "silent" note after 4 min. Better: count quiet time on the server in `lock_beat` (gap since previous `last_beat`) and show a running total. Void the lock when a gap happens on the same boot (phone stayed on, so it wasn't a dead battery). That keeps battery deaths from costing hours. Needs UI and an edit to `their_lock()`, whose live body isn't in this repo.
2. **Asking to unpair pauses all new and repeating locks for up to 7 days** unless the partner explicitly declines (`_no_lock_while_unpairing`, `_fire_due` skips the day). Anyone on a repeating lock can switch it off by tapping "leave". Consider letting existing repeats keep firing while a request is open, and push the partner louder.
3. **App limits trust the wall clock.** `usedToday` counts from local midnight, `GuardService` has no clock-jump detection (only `LockService` does). Moving the date back a day resets today's usage. Reuse the offset-vs-`elapsedRealtime` check from `LocalLock.beat()`.
4. **No retry if the lock service can't start.** `LockService.sync` swallows a failed `startForegroundService`; nothing tries again until the next trigger.
5. **Accessibility shortcut (volume-key toggle) can switch the guard off without opening Settings.** It's detected (`guard_off`, within a few seconds, voids the lock) but not prevented.
6. **Allowed apps are holes by design** (a browser or any app with links/share can reach everything). Helper packages `android` and `com.google.android.gms` are treated as part of an allowed app; gms hosts a lot of UI.
7. **Second account as "partner".** Pair two accounts you own; the other one unlocks you. Inherent to partner locks; friction only.
8. The guard's English-phrase matching has a language-independent fallback, but it only fires for pages that name LockIn with 1–2 switches. Non-English OEM pages for "put to sleep" may slip past.

## Not reviewed / flags

- Server function bodies that aren't in the repo (`lock_ask`, `lock_answer`, `lock_extend`, `_lock_tidy`, the `messages` and `devices` RLS). Anything the locked person can call directly is a path to the same problem as #1; worth a pass on the live project.
- `DynLoader`/`DynVerify` (remote code updates). Not read. If the update signing key leaks, that is full control of every phone.
- `app/keystore/keystore.properties` (with store/key passwords) and `google-services.json` are inside the zip. They're git-ignored, but don't share the archive; rotate the passwords if it has gone anywhere.

## Round 2 (prevent, don't just detect)

- **Device owner: every other app is suspended for the lock** (`LockGuard.holdOthers/releaseOthers`). Keeps the allowed apps, phone app, home, emergency, and the helper screens an allowed app needs. Re-applied when the partner changes the allowed list; released when the lock ends or on the next `sync` after a restart. Limits can't unsuspend what the lock holds.
- **MainActivity is no longer resizeable / PiP** (manifest). Cuts off the small-window case at the source on most phones. Caveat: on tablets and foldables this can letterbox the app (Android 16 ignores it on large screens); the tablet layouts need a look.
- **Escalation:** another app still on screen 9 s after the cover (Home sent each 3 s) locks the screen (device admin `lockNow`, else accessibility lock action). Unlocking brings the cover straight back.
- **Guard as main signal:** the accessibility window list is now preferred over the usage log, and a window change wakes the lock loop immediately (`typeWindowsChanged`) instead of waiting up to a second.
