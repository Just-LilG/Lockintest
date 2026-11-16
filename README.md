# LockIn

Study-accountability app: a partner locks your phone while you study. Kotlin + Jetpack Compose, Android 8.0+ (minSdk 26).

This slice has: the opening animation and welcome screen (one continuous scene), and real accounts on Supabase. Create account and Sign in open as a sheet on the welcome page itself: the mountain shrinks above it, and Sign up, Sign in, the 6-digit code, New password and the fingerprint offer slide into each other inside the sheet. Signing in goes to the Home page (see below).

## Build the APK

**Android Studio (easiest):** File > Open > this folder. Let Gradle sync, plug in a phone (or start an emulator), press Run.
To get a file you can install: Build > Build Bundle(s) / APK(s) > Build APK(s). It lands in `app/build/outputs/apk/debug/app-debug.apk`.

**Command line:** needs JDK 17 and the Android SDK (platform 35). Copy `local.properties.example` to `local.properties`, set `sdk.dir`, then:

    ./gradlew assembleDebug

**Signed release APK (what you send to phones):** run `scripts/make-signing-key.sh` once. It makes your own key
(`~/lockin-release.jks`, outside the project) and `keystore.properties` (git-ignored), then `./gradlew assembleRelease`
gives `app/build/outputs/apk/release/app-release.apk`, signed and installable. **Back up the `.jks` and remember the
password**: every later update (APK or code-only) must be signed with the same key. Without `keystore.properties`
the release build quietly uses the debug key, which differs per computer, so an APK from another computer says
"App not installed" until the old one is removed. Changing the key later means uninstalling the app once.

If you already have an older LockIn installed that was signed with a different key, uninstall it first (same package name, different signature = "App not installed").

## Backend (Supabase)

The app talks to Supabase over plain HTTPS (`data/SupabaseAuthRepository.kt`); the URL and publishable key are in `app/build.gradle.kts`. **Do the dashboard steps in `supabase/SETUP.md` once**, or sign-up hits the email limit and the emails won't contain the 6-digit code.

What the sheet does:

- **Sign up** is four steps with a progress bar: name, email (checked live against existing accounts), password + confirm, then the 6-digit code from the email. "Email me a new code" and "I already tapped the link" sit on the code screen.
- **Sign in**: email + password. "Forgot password?" sends a code, then asks for a new password.
- While the keyboard is up, the extras (Google button, switch links, hints) slide away so the field stays roomy.
- **Remember this phone** (on by default): keeps you signed in, stored encrypted with the Android Keystore. Next launch the welcome page says "Continue as <name>".
- **Fingerprint / face**: offered once after the first remembered sign-in. When on, "Continue as" asks for it first.
- **Password managers**: the fields are marked for autofill, so Google / Samsung / Bitwarden can fill and save.
- **Google sign-in**: the round Google button opens Google in the browser and returns to the app (PKCE). Needs the Google steps in `supabase/SETUP.md`.

The Supabase project has a `profiles` table (created automatically on sign-up, row-level security on) and a `check_email_available` function behind the live email check.

## Tuning the opening

- All timings: `intro/Timeline.kt` (seconds).
- Wind strength: `FlagMath.BASE_AMPLITUDE` in `intro/FlagMath.kt`.
- Ropes tying the climbers to the summit: `SHOW_ROPES` in `intro/ScenePainter.kt`.
- Climber posture and gait: `CROUCH`, `LEAN_DEG`, `HAND_LEAD` and the bone lengths at the top of `Rig` in `intro/ClimberRig.kt`. Their look (jacket, helmet, pack, harness) is in `intro/ClimberShapes.kt`.
- Welcome copy: the strings in `intro/IntroWelcomeScreen.kt`.

`intro/Geo.kt` is generated from the brand artwork; don't edit it by hand.

## Home and the other tabs (design only)

`home/` holds the signed-in app: four tabs that slide into each other. Everything still runs on placeholder data from `HomeSample`; buttons that need a backend (nudge, plan a session, edit profile) show "Coming soon".

- `HomeScreen.kt`: the shell. It owns the tab bar and the running session (the timer keeps going on any tab) and holds the Home tab.
- `PageScaffold.kt`: the frame and heading every tab page uses.
- `ClimbsPage.kt`: the month as a calendar of dots, three month numbers, and every session grouped by day with a filter. Tap a session to open its details.
- `PartnerPage.kt`: the rope between you and your partner, today side by side, stats together, planned sessions and an activity feed.
- `LockScreen.kt`: the screen shown while the phone is locked. Tapping Start on Home slides it up over everything: a 3, 2, 1 count while you rope up, then the rope pulls tight and a rolling timer runs over the mountain. Back does nothing, the system bars hide, and the session can only be ended by holding the button for about two seconds. It finishes on a summary. This is design only: nothing is actually blocked on the phone yet.
- `PartnerLockScreen.kt`: what you see when your partner locks your phone. A note from them, a countdown to when it opens, Message and Ask to unlock buttons, and a quiet Emergency release that needs a 5 second hold and tells them. It ends on Finished or Released. On the Partner tab, "Lock Kofi's phone" is the other side of this. Design preview only: the "Preview: Kofi locks me" button at the bottom of the Partner tab runs a 90 second pretend lock (delete it once real locks arrive).
- `ChatScreen.kt`: the chat with your partner (bubbles, event notes like "Kofi locked your phone", quick replies, typing dots). It opens from Partner > Message and from the lock screens, and Kofi answers with canned replies until there is a backend.
- `lock/LockAdminReceiver.kt`, `lock/LockGuard.kt`, `res/xml/device_admin.xml`: the real lock. LockIn registers as a device admin (uninstall protection, `force-lock`), and `LockGuard` pins the screen during a session. If LockIn has been made the device owner it runs a full kiosk instead (no home, no recents, no notification shade, phone app still allowed). Someone leaving mid-session is counted as a pickup and LockIn jumps back to the front (needs the "display over other apps" permission).
- `lock/ProtectionWatch.kt`: every few seconds, on a paired phone, checks that the switches that hold LockIn's protection (device admin, "display over other apps", app-time access, the accessibility guard) are still on, and reports one that went off without the partner's Allow (queued until there is a connection; also notes a start-up LockIn never saw, i.e. safe mode). A lock that had one switched off is voided: no session, and the partner is told. Runs from `usage/GuardService` (which now stays on while paired) and from the guard.
- `ProtectionScreen.kt`: You > Lock protection. Shows the level (Basic, Strong or Total), the switches that raise it, and the one command that makes LockIn the device owner: `adb shell dpm set-device-owner com.godbless.lockin/.lock.LockAdminReceiver` (USB debugging on, no accounts signed in). A plain device admin can still be switched off in Settings; only device owner makes that impossible.
- `ClimbScene.kt`: the mountain, climbers, rope and flag, shared by the Home card and the lock screen.
- `YouPage.kt`: altitude and rank (one focused hour is 100 m), the daily goal stepper, lock settings, and account rows including Sign out.

- `AlpineHero.kt`: today's climb. The welcome page's mountain, climbers and flag, where how high each climber is shows today's focus (you and your partner), with a start/end session button. Starting a session pulls the rope taut and swaps the big number for a live timer.
- `HomeParts.kt`: partner card, count-up stat tiles, the week drawn as a mountain range, recent climbs, and the floating tab bar.
- `HomeData.kt`: every number on the page is placeholder data from `HomeSample`. This is the one place to replace with real sessions and a real partner.
- `HomeIcons.kt`: the line icons, all drawn in code.

## Study kit (`study/`)

A calculator, a textbook reader and a flashcard quiz. They open from the **Study kit** card on Home and from a
dock on both lock screens, so they work while you are locked in. Everything is stored on the phone; nothing is uploaded.

- `StudyStore.kt` keeps textbooks and flashcards. PDFs are copied into `filesDir/study/<id>.pdf` with a cover PNG;
  `books.json` and `cards.json` sit beside them. Cards use Leitner boxes (back in now, 1, 3, 7 or 14 days);
  a miss sends a card to box 0.
- `PdfReader.kt` reads with Android's `PdfRenderer` (one render lock, pages drawn on `Dispatchers.IO`, 64 MB bitmap
  cache). Pinch to zoom, a page-jump slider, dark pages, and your place is remembered. "Make a card" opens the card sheet.
- `LibraryScreen.kt` lists the books and adds one through the system file picker. Adding is switched off during a
  lock, because a locked phone can't open other apps (the picker is one). Add books before you lock in.
- `QuizScreen.kt` has a hub (due count, style, book filter, card list), a session (flip cards, or multiple choice
  with wrong answers taken from your other cards, which needs 4 different answers) and a result. Sessions are 20 cards.
- `CardEditor.kt` is the sheet for writing a card (question, answer, book, page).
- Calculator (`study/`): `Calc.kt` is the expression engine (variables, calculus, exact answers), `CalcTools.kt` holds SOLVE, CONST, CONV, BASE-N, STAT, DISTR, MATRIX, VECTOR complex maths and equations (EQN), `CalcModel.kt` is the keypad brain (all key layers, CALC/SOLVE prompts, history), and `CalculatorScreen.kt`, `CalcSheets.kt`, `CalcPages.kt` draw it. See HANDOFF 0.8.4 and 0.18.2.
- `StudyTools.kt` has `Tool`, `ToolDock`, `StudyKitCard` and the shared `StudyScaffold` / `StudyField`.

Not done: searching the text of a PDF (`PdfRenderer` draws pages but can't read them) and making cards with AI.

## Partner pairing and chat (real backend)

- **Pairing** (`home/PairingPage.kt`, `home/PartnerState.kt`): the Partner tab shows your invite as a QR code and a spaced code (tap to copy), with Share and New code, plus a box to enter theirs. Whoever enters the other's code finishes the pairing. Codes are 8 characters and last 24 hours. Once paired, the Partner tab turns into the full page, with "Unpair" at the bottom (asks twice, deletes the chat). Until then Home shows an "Invite your partner" card.
- **Chat** (`ChatScreen.kt`, `ChatState` in `PartnerState.kt`): real messages, saved on Supabase and fetched every 2.5 seconds while the chat is open and every 20 seconds otherwise (only while the app is on screen). Nudge, "asked to unlock" and the emergency release also appear as notes in the chat for both of you.
- **Typing dots**: while the chat is open, each phone tells the server every ~1.2 seconds whether a key was pressed in the last 3 seconds, and learns whether the other side is typing (`typing_tick` on Supabase). The dots show under the last message and the header says "typing…". They stop when the message arrives or after about 5 seconds of silence.
- `data/PartnerRepository.kt` makes the calls over plain HTTPS, with the signed-in person's token, and retries once with a fresh token if it expired (`AuthRepository.accessToken`). Setup SQL is summarised in `supabase/partners.sql`.
- Still sample data: "Coming up", the next-session card, and the daily goal (fixed at 2 hours). Locking your partner's phone from their side is not wired yet; the lock screens still run from the preview buttons.
- Messages arrive by checking while the app is open, and by push while it is closed (see Push notifications below).

## QR, scan and pairing links

- **Three ways in, one code.** Type it, scan the QR, or tap a link. All three end in `PartnerState.preview(code)`, which asks the server who owns the code (`invite_owner`) and shows "Pair with X?" before anything is joined.
- **Link format** (`data/PairLink.kt`): `https://lockin.secrets.quest/join/CODE` is what Share sends and what the QR holds. `lockin://pair/CODE` also works. `PairLink.parse` accepts a plain code, either link, or `?code=`, and rejects other hosts and codes with 0, 1, I or O. The host must match the manifest and `pair-page/index.html`.
- **Scanner** (`home/ScanScreen.kt`): CameraX plus ML Kit barcode scanning, QR only, asks for the camera permission when opened. "Type the code instead" is always there. The QR is drawn by ZXing (`home/QrCode.kt`).
- **Link page** (`pair-page/`, not part of the Android build): a static page on Vercel (project `lockin-pair`, domain `lockin.secrets.quest`). It shows the code and an "Open in LockIn" button. On Android 12+ an unverified https link opens in the browser rather than the app, so the button uses an `intent://` URL that opens LockIn if it is installed and returns to the page with a note if not. To redeploy: `cd pair-page && vercel --prod`.
- **Not done:** verified App Links (`/.well-known/assetlinks.json` with the signing certificate's SHA-256) would let the https link open the app directly with no page in between. Add it once you have a release signing key.
- **Needs a Gradle sync** after updating: CameraX 1.4.1, ML Kit barcode-scanning 17.3.0 and ZXing core 3.5.3 were added.

## Push notifications (Firebase)

- **Firebase project:** "My First Project" (id `project-c6518a80-bca4-4b24-b07`), with the Android app `com.godbless.lockin`. Its `app/google-services.json` is in the project; it holds public client identifiers, not a private key. The Google services Gradle plugin reads it.
- **Phone registration** (`push/PushRegistrar.kt`): on opening Home the app gives the server this phone's Firebase token (`register_device`). Signing out removes it first (`unregister_device`). Signing in on the same phone as someone else moves the token to them.
- **Sending:** after a chat message or nudge is saved, the app asks the Edge Function `push` (source in `supabase/functions/push/index.ts`) to notify your partner. The function checks the message is yours and under two minutes old, finds your partner's phones, and sends a data message through Firebase Cloud Messaging (v1). Dead tokens are cleaned up.
- **Showing it** (`push/LockInMessagingService.kt`, `push/Push.kt`): the app builds the notification itself, so it stays quiet while LockIn is on screen (the chat already refreshes) and shows a single replaceable notification otherwise. Tapping it opens the chat. Android 13+ asks for the notification permission once, the first time you have a partner.
- **One secret to add (not in the project):** the function signs in to Google with a service account. In Firebase, Project settings > Service accounts > Generate new private key, then in the Supabase dashboard open Edge Functions > Secrets and add `FIREBASE_SERVICE_ACCOUNT` with the whole contents of that file. Keep the file out of the project and out of version control. Until it is set, the function answers `push_not_configured` and no push is sent (chat still works). A signed-in `GET /functions/v1/push` answers `{configured, project}` so you can check the secret without sending anything. The function tolerates the JSON being pasted in quotes.
- **Not yet:** locking your partner's phone remotely. That will use the same path with a different message type.

## App times and limits (`usage/`)

When you are paired, each of you shares app times with the other by default (turn it off under "Your phone" on the Partner tab).
You see how long your partner used each app today and over the last 7 days, and you can set a daily limit on any of them. It applies on their phone straight away and they see a note in the chat.

How it works on the phone:

- **Usage access.** Android's "usage access" switch for LockIn must be on. The Partner tab has a button that opens that Settings screen. Without it nothing is read or reported.
- **Guard service** (`GuardService`, a foreground service with a quiet notification). It reports today's times every 10 minutes (the last 7 days every hour), fetches limits, and watches which app is in front once a second while limits exist.
- **When time is up.** If LockIn is device owner, the app is suspended until midnight (`setPackagesSuspended`). Otherwise `BlockActivity` covers it, which needs "display over other apps" (the Partner tab asks); without that you only get a notification.
- **Never limited:** LockIn, Settings, emergency, system UI, the dialer, messages, and home launchers.
- **Fails open.** If usage access is switched off, the limit is removed, or you sign out or unpair, suspended apps are switched back on.
- Server: see the `usage_sharing_and_limits` notes in `supabase/partners.sql`. Android keeps usage events for only a few days, so a phone that was off for a week can't send older days.
- Tests for the time maths are in `app/src/test/.../usage/UsageMathTest.kt`.

## Updates without an app store (`update/`, `scripts/publish-update.sh`)

LockIn checks for a newer build each time it opens (at most every 6 hours) and from You > App updates. When one exists, a card slides down on Home. Update downloads the APK, checks its checksum and opens Android's installer, which asks you to confirm.

To publish a release:

1. Raise `versionCode` in `app/build.gradle.kts` (and `versionName`). Each release must be higher than the one before.
2. Build the APK.
3. `export SUPABASE_SERVICE_ROLE_KEY=...` (Supabase dashboard > Project Settings > API). Keep it private.
4. `scripts/publish-update.sh path/to/app.apk "one-line note"`. Add `MANDATORY=1` to hide "Later".

Good to know:

- **Same signing key.** Android only installs an update signed with the same key as the installed app. Always build on the same machine (the debug key lives in `~/.android/debug.keystore`), or make a release keystore and keep it safe. Lose it and everyone has to uninstall.
- **First time.** Android asks you to allow LockIn to install apps. The Update button sends you to that screen once.
- **Not silent.** The person always taps Install. It only changes the Kotlin app: Supabase and the push function update on their own.
- **Storage.** The public `updates` bucket holds `latest.json` and the APKs. Nobody but the service key can write to it.
- **Play Store.** If you publish there later, use Play's in-app updates and drop this.

## Games: tic-tac-toe and Word Duel (`lock/`, `live/`)

Both live in the chat's Game picker. Moves go through server RPCs; a private Supabase Realtime channel (`live/LiveChannel.kt`) adds the live parts: in tic-tac-toe you see your partner's finger and the mark they are carrying, and moves land almost at once; in Word Duel the setter watches the guesser type, flips happen on both phones together, and the setter can send floating emoji. If the live line is down the games poll instead. See HANDOFF.md, 0.23.0.

## Locking your partner's phone (real)

On the Partner tab, pick a time (25 m to 2 h) and tap Lock. The server stores a lock with a fixed end time and pushes your partner's phone. Their phone brings LockIn to the front and the lock screen takes over (full kiosk if LockIn is device owner, otherwise screen pinning). You see a live countdown and can unlock them any time.

- **Clock.** The server owns it. If their phone is offline when you lock, the lock still ends at the same time and starts counting as soon as they're back online.
- **Their side.** Message, ask to unlock (a push to you), or the 5-second emergency hold. The emergency release works offline: the phone lets go first and tells the server when it can.
- **Unpairing** ends any lock.
- **Bringing the app up from the background** needs "display over other apps" on their phone (the same permission the app-limit block screen uses). Without it they get a notification and the lock starts when they open LockIn (it checks every few seconds while open).
- **Not covered yet:** if their phone is off or the app is force-stopped, the lock starts when they next open LockIn. Locks aren't added to session history.

## Code-only updates (`dyn/`, `scripts/publish-dynamic.sh`)

Most changes are Kotlin code. For those, phones can switch to new code without Android's installer: a small signed bundle (manifest, classes.dex, signature) is downloaded, checked, and loaded the next time the app starts. The card says "Updated. Restart LockIn to finish." and a notification says the same.

**One-time setup**

1. `scripts/dynamic-keygen.sh` makes the signing key (private half in `~/.lockin/dynamic-signing.pem`, public half in `app/dynamic-public-key.txt`). Back the private key up and never share it. Without `dynamic-public-key.txt` the app accepts no code update at all.
2. Build and install an APK made after that (`./gradlew assembleDebug`), or publish it with `publish-update.sh`. This APK carries the loader and the public key, so phones need it once.

**Each code update**

1. Edit Kotlin code, then `./gradlew dynamicDexDebug` (`dynamicDexRelease` for release builds).
2. `export SUPABASE_SERVICE_ROLE_KEY=...` then `scripts/publish-dynamic.sh debug "one-line note"`.

**What it can't change.** Anything outside Kotlin code needs a new APK: the manifest and permissions, resources (layouts, drawables, strings, fonts), libraries and versions, `build.gradle.kts`, `google-services.json`, and the `dyn` package. The build computes `STABLE_API` from those files; if it changes, phones ignore code updates built from it, and the update card offers the APK (`publish-update.sh`) instead. If you aren't sure, publish an APK.

**Safety**
- The bundle must pass an ECDSA signature check against the key built into the app, and its checksum must match the manifest. Anything else is ignored, so a tampered file or someone else's upload does nothing.
- A bundle is for one build type (debug or release) and one `STABLE_API`.
- **Rollback.** A new version that doesn't stay up for ten seconds in three starts in a row is deleted and the installed code runs again. That version is never offered again; publish a newer one.
- Android 9 and up only. On Android 8 the installed code always runs and updates go through the APK.
- The downloaded dex is made read-only before loading (Android 14 requires it).
- Google Play doesn't allow apps to change their own code outside Play's updates. This is for sideloaded builds; drop it before publishing there.

**Tests for code updates** (`app/src/test/.../dyn/`, run with `./gradlew testReleaseUnitTest`)
- `DynVerifyTest` builds signed bundles on the fly and checks the gate: a good one passes; a changed byte in the code, a changed manifest, a signature from another key, the wrong `STABLE_API`, the wrong build type, no key in the app, more than one dex file or anything but a full payload are all refused.
- `DynUpdatesParseTest` checks how `latest.json` is read, including that only addresses under your own Supabase storage are ever downloaded from.
- `DynRealBundleTest` is a last look at a real update before you upload it. It is skipped unless you point it at the folder holding the bundle and its `latest.json`; run it from the same sources the installed app was built from:
  `OTA_DIR=/path/to/folder OTA_BASE_VERSION=<installed DYNAMIC_VERSION> ./gradlew testReleaseUnitTest --tests '*DynRealBundleTest'`
- Not covered: the phone actually loading the new code, restarting, and the three-crashes rollback. Those need a real device.
- The tests need the real `org.json` on a plain JVM, so the root `build.gradle.kts` adds it to the test classpath only. It is deliberately not in `app/build.gradle.kts`, because that file feeds `STABLE_API` and editing it would change the build number.

## Sessions and stats (real data)

- Ending a lock (hold the button on the lock screen) saves the session: when it started, how long, pickups. Under 30 seconds is ignored. It is written to `pending_sessions.json` on the phone first and sent to Supabase afterwards, so a session that ends offline is not lost; it is sent the next time the app is on screen. Each session has its own id, so a retry never doubles it.
- `data/SessionRepository.kt` does the saving and fetching (the last 62 days of yours and your partner's, plus all-time totals from `session_totals()` on the server). Partner locks are not recorded yet because real partner locks don't exist yet (the preview doesn't count).
- `home/Stats.kt` turns the sessions into every number the pages show: today, streak, this week against last week, the month calendar, history, recent climbs, the activity feed, altitude, and the "together" tiles. It has no screen code in it, so `app/src/test/.../StatsTest.kt` checks it with plain JUnit (`./gradlew test`). A session of 90 minutes or more counts as a summit.
- `home/SessionState.kt` keeps the numbers fresh (on open, then every 30 seconds) and counts a session the moment it ends, before the server confirms it.
- The best streak on the Partner tab is the longest run of days you both locked in, over the last 75 days.

## Welcome to Home, as one scene

Home sits underneath while the welcome page, drawn on top, closes in on the card where Home keeps its
mountain (`LockInApp.kt`, `intro/IntroWelcomeScreen.kt`, `home/SceneHandoff.kt`). The headline and buttons
drop away, the contour lines and page shrink into the card, the climbers walk from their welcome spots to
today's place on the mountain, and Home's rows rise into the space the page uncovers. The two pages use
climbers with the same stride, so the poses at the first and last frame are identical and there is no cut.
Signing out runs the move backwards. There is no `NavHost` any more: `LockInApp` holds both pages itself.

## Daily goal and planned sessions

- **Goal:** each of you sets a daily goal on the You tab (15 minutes to 16 hours). It is saved on the server, and
  the mountain on Home, the lock screens and the weekly chart all use it. Each climber climbs toward their own goal.
- **Plans:** "Next session" on Home and "Coming up" on the Partner tab show the sessions you and your partner
  planned together. Either of you can add one (name, day, time, length) or cancel one; the other gets a push and a
  line in the chat. Tap a session on the Partner tab to see who planned it and to cancel it.
- **Reminders:** a notification ten minutes before a planned session (switch on the You tab). The alarms are inexact
  on purpose, so no special permission is needed; they can arrive a few minutes late while the phone dozes, and
  they are set again each time the app opens.
- This change adds a receiver to the manifest, so it needs a full APK install once (it cannot go out as a code-only update).
- Server: migration `goals_and_plans`, edge function `push` v6 (adds `plan_set`, `plan_canceled` and a `detail` field).

## Partner page

The Partner tab shows only real data: the status line comes from when their phone last reported in, Nudge sends a real nudge to the chat, and the old "Preview: … locks me" button is gone. The page is ordered Partner card, Today, Lock, Coming up, their phone (top three apps, "All apps and the week" expands), Together (stats and the latest four events), then "Your phone" (sharing and limits on you) behind one tap.

If the lock button seems to do nothing, run `adb logcat -s LockInLock` while tapping it. It prints `lock requested`, then `lock set` or `lock failed: <reason>`. The card also shows the reason on screen.

## Permissions page

LockIn lists everything it needs (notifications, app times, stay on top, device admin, keep running) on one page. It slides up when someone signs in, and again each time the app is opened while anything is still off. "Allow <next>" walks through them one at a time, and "Skip for now" closes it. While anything is off, Home shows a "switches still off" card that reopens the page. All of it lives in `home/PermissionsScreen.kt`; no manifest change, so a code-only update works.

## Locking your partner

The big "Lock <name>'s phone" card sits on Home (under the mountain) and at the top of the Partner tab. It opens the Lock page (`home/LockPartnerPage.kt`):

- **Set a lock:** any length from 5 minutes (stepper and presets), a message they see on their lock screen, "No unlock requests" (strict), and "Lock together" (your own focus session starts too).
- **Watch it:** live countdown, pickups their phone reports, unlock requests you approve or answer "Not yet", +15 / +30 minutes, Message, Unlock early.
- **Their lock screen:** shows your message, lets them ask to be unlocked with a quick reason (unless strict), and shows "Not yet" for five minutes after a no. The emergency release always works and tells you.
- **History and rules:** past locks from both sides with pickups and how each ended; each person can switch locks off or cap the longest lock they accept.

If a lock does nothing, `adb logcat -s LockInLock` shows each request and its result. Server side lives in `supabase/partners.sql` (Lock v2) and the `push` function (v7).

### Scheduled locks

In the Lock page, "Starts" can be Now, 30m, 1h, 3h or 6h (up to 7 days on the server). The server stores `starts_at` and counts the length from there. Their phone gets a heads-up push (`lock_planned|minutes|epochSeconds`), sets an inexact alarm (`LockStartAlarm` in `push/PlanReminders.kt`, riding on the existing `PlanReminderReceiver` so no manifest change) and the lock screen opens when it begins. Alarms are inexact, so a dozing phone can start it a little late; the lock's own clock is the server's. Cancel from the Lock page ("Cancel lock", event `lock_canceled`). Lock together is only available for "Now".

### Lock hardening (0.5.0)

- **Exact times.** `push/Alarms` sets alarms with `setExactAndAllowWhileIdle` when the phone allows it (`SCHEDULE_EXACT_ALARM`; Android 12 grants it, 13+ asks), otherwise inexact as before. Used by scheduled-lock starts and plan reminders. The permissions page has a new "Exact times" row.
- **Survives a restart.** `LockStartAlarm` keeps the scheduled start and the end of a running lock in prefs (`lock_alarm`). `usage/BootReceiver` calls `LockStartAlarm.rearm`: a future start is re-armed; a start that passed while off, or a lock still running, pings Home and brings LockIn to the front.
- **Lock together ends with the lock.** `HomeScreen` keeps `togetherWith` (the id of their lock). When that lock ends (time, unlock, emergency) the locker's own session stops and is saved.
- **Partner locks count in stats.** When a lock on you ends, it is saved as a session titled "Locked by <name>" with the time it really held. Its id is `lockSessionId(lockId)` (name-based UUID), so the server's ignore-duplicates keeps one copy.

## You tab (0.5.1+, decluttered)

- Layout, top to bottom: `ProfileHeader` (avatar in a ring filled by today vs goal, name, join date, Edit), `AltitudeHero`, then `TodayCard` and `MonthCard` side by side (a `Row` with `IntrinsicSize.Min`, so they are always the same height), the settings list, and Sign out as a plain link.
- Motion (slides and springs only, no fades): ridges spring up into the hero, contour lines drift sideways in a seamless loop, your dot walks the trail while the altitude counts up, the today ring fills on a spring, the goal text rolls up or down, week dots pop in one by one, calendar squares grow in across the month, and every tap target sinks and springs back (`Modifier.springClick`).
- `home/YouPages.kt`: `YouState` (profile from `profile_me`, rename via `set_display_name`) and the pages Edit profile, Notifications (`push/NotifyPrefs`), Your partner, Sign-in and security. `HomeScreen` opens them as slide-in overlays (`youPage`).
- Notification switches only decide what is shown: `LockInMessagingService` checks `NotifyPrefs.allows(event)` just before showing.

## Home, decluttered

- Order: greeting, the mountain card, (invite card when unpaired), `StudyKitRow` (three slim chips; Quiz shows a lime count when cards are due), the week card (now 104 dp, with "N-day streak · M climbs this month" under the total), Recent climbs (two, "<1m" for short ones).
- Gone from Home: the big lock card, the next-session card, the partner card and the three stat tiles. Plans stay on the Partner tab.
- On the mountain card: `LockLine` under Start (lock icon, live state: waiting, time left, or "asked to be unlocked" with a lime ring; the wording slides when the state changes, keyed by `LockLineText.kind`).
- Nudge: tap your partner's climber (or their tag). `ClimbScene` takes `nudge` (0 to 1) and `onPartnerTap`; the hit area is a 56 dp circle around their head. The 1.6 s animation: a lime spark arcs over the summit from you to them, then they hop, the rope twangs, two rings spread out (their stroke thins to nothing, no fades), and their tag bounces and reads "Nudged!" in lime. Haptics on tap and when it lands. One nudge at a time. The footer says "Tap <name> to nudge".

## Mountain scene fixes (all pages: welcome, Home, both lock screens)

- Ropes tie on at `ROPE_TIE_X/Y` (621, 478), the middle of the pole's foot. They used to aim at x 614, left of the pole.
- The flag cloth is now built in code (`FlagMath.top/bottom`, one swallowtail `NOTCH`, `SAMPLES` points per edge) instead of the traced outline, whose three loose tails looked like they tore off and rejoined. It starts on the pole's edge (x 628.5), so there is no gap. The traced `Geo.cloth` is no longer used.
- Climbers (`ClimberRig.pose(..., wave)`, `ClimberShapes`): idle life on each climber's own rhythm (breathing that lifts the chest, a slow weight shift over planted feet, a look up at the summit every ~8 s, re-planting the axe every ~6.5 s), a wave (near arm up and swinging) used by the nudge, plus an ice axe with a lime grip, shading on the jacket's back, a lime zip and helmet band, a goggle glint and helmet highlight, and a rope coil on the pack.
- These scene files are plain Kotlin, so they can be compiled and rendered off-device: compile `ClimberRig, ClimberShapes, Ground, Geo, Easing, FlagMath` with a small dumper and draw the prims with PIL.

## Home motion

All slides, springs and growth; nothing fades. Helpers in `home/HomeMotion.kt` (`StaggerText`, `rememberPop`, `rememberInView`, `rememberSlideIn`, `homeSpring`) and `ui/Components.kt` (`riseSpring`: a longer rise with a small overshoot and a grow from 95.5%).
- Greeting and name: letters rise in one by one; the avatar pops in. The header drifts at 0.28x scroll so it peels away.
- Mountain card: rows cascade in with `riseSpring`; the scene drifts at 0.22x scroll inside the card (parallax); today's minutes count up; a slanted band of light sweeps across Start every ~4 s (`StartSheen`).
- Lock line: the padlock rattles at the start of each 5 s loop while nothing is set; while a lock runs, a lime ring around it drains with the time left.
- Study chips pop up in turn; their icon tips when pressed.
- Week card and Recent climbs wait until they scroll into view (`rememberInView`): the ridge rises and the total counts up, today's pennant flutters, and the climb rows slide in from the right one after another.
- `PageScaffold` takes an optional `scroll` and `entranceMillis` (other pages are unchanged).

## Lock hardening, round 2 (0.6.0, full APK)

`lock/LockService.kt` (foreground service, specialUse) holds a partner's lock whether or not LockIn is open:
- **Cover:** whenever LockIn isn't on screen, a full-screen `TYPE_APPLICATION_OVERLAY` window (`lock/LockCover.kt`, Compose in a service with its own lifecycle and saved-state owner) covers every app: home, recents, Settings (so no force stop or admin switch-off from there), split screen. Each time you leave LockIn counts as an escape; the cover shows the count and that your partner can see it. The cover steps aside while a phone call rings or is on (`AudioManager` mode), for 45 s after "Emergency call" (which also warns the partner), and when the screen is off.
- **Survives everything we can survive:** `lock/LocalLock.kt` keeps the lock on the phone, counting time on the since-boot clock (immune to Settings clock changes), falling back to the wall clock after a restart until the server answers. Started from app start, boot, the `lock_set` push (before LockIn is even opened), Home, and the admin receiver. Home restores the lock screen from it when offline.
- **Server is the judge:** `lock_beat(p_pickups, p_escapes)` every 15 s returns the time left or that the lock is over (unlock, emergency release, time up). `partner_locks.last_beat` and `escapes` are new; `their_lock` returns `beat_ago` and `escapes`.
- **Warnings to the locker** (`lock_tamper|<kind>` chat events, worded in the push function v8 and in `PartnerState`, sent once per lock and kind, queued while offline): `stopped|<min>` (heartbeat gap over 90 s: force-stopped, swiped on aggressive phones, or phone off), `overlay_off`, `admin_off` (also from `LockAdminReceiver.onDisabled`; the disable prompt now warns mid-lock), `time_changed` (wall clock jumped more than 2 min against the since-boot clock), `emergency_dial`.
- **The locker's live card** shows "Tried to leave N times" and a red notice when the phone hasn't checked in for a minute.
- **Device owner (Total):** during a lock `LockGuard.hardenForLock` adds DISALLOW_SAFE_BOOT, FACTORY_RESET, CONFIG_DATE_TIME, DEBUGGING_FEATURES, ADD_USER, blocks uninstalling LockIn and disables the status bar; all undone when the lock ends (and on the next start if it ended while LockIn wasn't running). `clearOwner` is refused during a lock.
- **Always open, by design:** phone calls, the emergency dialer, and the 5-second-hold emergency release in LockIn (which tells the partner).

What Android doesn't let a normal app block (only device owner can): the notification shade and quick settings (including inline replies), and safe mode. A restart into safe mode or a power-off is still reported afterwards as "stopped".

Debug: `adb logcat -s LockInLock`.

## Unpairing needs both of you (0.6.0)

Server SQL: `supabase/migrations/20261113_unpair_needs_consent.sql` (applied 2026-11-13; push function v9).
- `leave_partnership()` now returns `{state}`: cancels an unjoined invite instantly; otherwise records a request (`partnerships.unpair_by/unpair_at`, event `unpair_asked`), or agrees if the other person asked first. Refused with `locked_now` while a lock is on you.
- `unpair_answer(p_agree)` (event `unpair_declined` on no), `unpair_cancel()`.
- A request unanswered for 7 days goes through (checked in `my_partnership` via `_unpair_due`), unless a lock is on the person who asked. While a request is open, a trigger refuses new locks on the requester (`unpair_pending`), so nobody can be held by back-to-back locks.
- `my_partnership` returns `unpair_by` ("", me, them) and `unpair_due` (epoch s).
- `lock_tamper(p_kind, p_detail)`: the locked phone's warnings now go through this RPC (the messages policy only lets the app insert nudge/asked_unlock/emergency_release itself, so 0.6.0's direct inserts would have been refused). It checks a lock is on the caller (running, or ended in the last hour for warnings queued offline).
- TRUNCATE revoked from anon/authenticated on partnerships, partner_locks, profiles, messages.
- App: `UnpairCard` (Partner tab and You > Your partner) shows ask / waiting with "Take it back" / your partner's request with Agree or Not now. Push wording for `unpair_asked` and `unpair_declined` is live (push v9).

Also: "See all" on Home's Recent climbs opens the Climbs tab; the unused `HomeSample` data is gone.

## Repeating locks (0.6.0)

Server SQL: `supabase/migrations/20261113_repeating_locks.sql` (applied 2026-11-13; push function v10).
- `lock_schedules` table (RPC-only): locker, target, `days` bitmask (bit 0 Mon ... bit 6 Sun), `start_minute`, `seconds`, `tz` (the locker's phone zone), message, strict, `last_fired_on`.
- `schedule_add(p_days, p_start_minute, p_seconds, p_tz, p_message, p_strict)` (same rules as `lock_partner`: allowed, max length, max 8 repeats, refused while the target has an unpair request open; event `lock_repeat|min|days|start`), `schedule_remove(p_id)` (locker only; event `lock_repeat_off|...`), `lock_schedules()` (both directions, `mine` flag).
- Firing: `_fire_due(target)` creates the lock (starts_at = today's start in the schedule's zone, so a late fire is just shorter), at most once a day, only inside the window [start, start + length), only if no lock is running and the target allows it. pg_cron job `lockin-repeating-locks` runs `_fire_due_all()` every minute; `my_lock()` and `their_lock()` also fire due repeats first, so a phone that wakes on time gets the lock that second. Unpairing deletes the repeats between the two.
- App: Lock page > "Repeating locks" (`home/RepeatLocks.kt`: cards with day dots and delete; composer with day chips and quick picks, a 15-minute time stepper, length chips capped by their rules, note, no-unlock-requests). The locked side sees "Repeating locks X set on you" in the rules card.
- The locked phone sets an exact alarm for the next start (`push/RepeatAlarm.kt`, through `PlanReminderReceiver`), remembered for restarts; when it fires it calls `my_lock` (which starts the lock) and hands it to `LockService`, with LockIn closed or not.

## Partner tab, simplified, and devices (0.6.0)

- Top: `PartnerTopPager` (`home/PartnerTop.kt`), one card you swipe (pages tilt and shrink a touch, lime pill dots). Page 1: rope with ring avatars (today vs each goal) and today's minutes, status and who's ahead, Message, a round Nudge (bell wiggles), `LockLine`. Page 2: their phone today (total, top 4 apps with growing bars, "Limited" tags, tap to limit, "All apps and the week" opens the full `TheirPhoneCard` below).
- **Devices:** `DevicesCard` lists every device your partner uses LockIn on (name from Settings, model, Android version, last seen) with "Lock all N devices" (a lock is per person, so it covers every device). During a lock, each device shows "Locked · checked in …" or, in red, "Quiet for … · locks when it's back", plus escapes per device. Your own devices are under "Your phone" (remove old ones).
- Server (`supabase/migrations/20261113_devices.sql`): `devices`, `lock_device_beats`, `device_checkin`, `device_forget`, `partner_devices`, `my_devices`; `lock_beat` gains optional `p_device`. Devices check in every 5 min from Home; `LockService` sends its device id with each beat. `data/DeviceRepository.kt` has `ThisDevice` (an install id kept in prefs, not a hardware id).
- Signing out is refused during a partner's lock (it would stop that phone checking in), and signing out removes the phone from your devices.
- Coming up, a compact `TogetherCard` (sessions, focused, best streak, latest activity), "Your phone" (rotating plus), and the unpair link follow.

## Notifications, polished (0.6.1, full APK)

`push/Push.kt` and `push/NotifyActionReceiver.kt`:
- **Channels**, grouped under "Partner": Messages (short buzz), Locks (strong double buzz), Nudges (quick triple tap), Plans, App limits. The old single "partner" channel is deleted on first use. The Notifications page (You tab) has a "Sound and vibration" link per kind that opens that channel's settings.
- **Own slots**: messages 2001, locks 2002, nudges 2003, plans 2004, limits 2005, pairing 2006. Nothing replaces a different kind.
- **Messages are a conversation**: `MessagingStyle` with the partner as a `Person` (lime letter avatar drawn in code), the last 6 lines (kept in prefs `notif_chat`, cleared when LockIn opens or on "Mark as read"), a long-lived shortcut `partner_chat` so Android lists it under Conversations, and inline **Reply** (`RemoteInput`; sends through `PartnerRepository.send`, then shows your line).
- **Actions**: unlock request → Unlock / Not yet (`lock_answer`); nudge → Lock in; lock warning → Open lock (and it shows even with LockIn open).
- **Taps** open the chat, the lock page (`openTarget = "lock"`) or the Partner tab (`"partner"`), via `MainActivity.handleLink` and `LockInApplication.openTarget`.
- **Lock notification** ("Locked by …") counts down by itself (chronometer to the end time), says when the phone opens again, shows tries to leave, and refreshes when the server corrects the time or an escape is counted.

- **Message sounds**: pick your own message sound (Fah, Whistle, Sneeze and more) in You → Notifications. Your partner hears it when you message them.
