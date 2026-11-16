# Supabase setup checklist (once)

Project: **LockIn** (`huhhzfbmsdajkppllako`). Dashboard: https://supabase.com/dashboard/project/huhhzfbmsdajkppllako

Do 1, then **either** 2A or 2B, then 3 and 4. Google (5) is separate and optional until you want it.

## 1. App link (stops emails pointing at localhost)
Authentication → URL Configuration:
- **Site URL**: `lockin://auth/callback`
- **Redirect URLs**: add `lockin://auth/callback`

## 2. Emails: pick one

The built-in sender allows only a few emails an hour, which is why sign-up says "Too many emails right now".

### 2A. Quick testing: no emails at all
Authentication → Sign In / Providers → Email → turn **Confirm email** OFF → Save.
Sign-up signs you straight in (the code step is skipped). Turn it back on before launch.

### 2B. Real emails with the 6-digit code (Resend + secrets.quest)  ← recommended
The domain `secrets.quest` is already set up in Resend (DKIM and SPF verified through Vercel DNS).
Supabase → Authentication → Emails → **SMTP Settings** → Enable custom SMTP:
- Sender email: `noreply@secrets.quest`, Sender name: `LockIn`
- Host `smtp.resend.com`, Port `465`
- Username `resend`
- Password: the Resend API key named "LockIn Supabase SMTP" (sending-only, limited to secrets.quest)

Then Authentication → Rate Limits → "emails per hour" → 30 or more.
Optional: Resend lists one more record (`rsend` CNAME → `send.forge.rmta.net`) as pending; add it in Vercel DNS to make the domain show fully verified. Sending already works without it.

## 3. Put the 6-digit code in the emails (for 2B)
Authentication → Emails → Templates. Paste into each. `{{ .Token }}` is the code, `{{ .ConfirmationURL }}` the link.

**Confirm signup** (subject `Confirm your LockIn email`)
```html
<h2>Confirm your email</h2>
<p>Enter this code in the LockIn app:</p>
<p style="font-size:32px;letter-spacing:6px;font-weight:700">{{ .Token }}</p>
<p>Or, on the phone with LockIn installed, tap: <a href="{{ .ConfirmationURL }}">Confirm email</a></p>
```

**Magic Link** (subject `Your LockIn code`)
```html
<h2>Sign in to LockIn</h2>
<p>Your code:</p>
<p style="font-size:32px;letter-spacing:6px;font-weight:700">{{ .Token }}</p>
<p>Or tap: <a href="{{ .ConfirmationURL }}">Sign in</a></p>
```

**Reset Password** (subject `Reset your LockIn password`)
```html
<h2>Reset your password</h2>
<p>Your code:</p>
<p style="font-size:32px;letter-spacing:6px;font-weight:700">{{ .Token }}</p>
<p>If you didn't ask for this, ignore this email.</p>
```

## 4. Code length
Authentication → Sign In / Providers → Email → "Email OTP length" = **6**.

## 5. Google sign-in
No Android keys or SHA-1 needed; the app opens Google in the browser and comes back through `lockin://auth/callback`.
1. Google Cloud Console → create/select a project → **APIs & Services → OAuth consent screen**: External, app name LockIn, your email. While it says "Testing", add your Google account under Test users.
2. **Credentials → Create credentials → OAuth client ID** → type **Web application** → Authorized redirect URI:
   `https://huhhzfbmsdajkppllako.supabase.co/auth/v1/callback`
   Copy the Client ID and Client secret.
3. Supabase → Authentication → Sign In / Providers → **Google** → enable → paste the Client ID and secret → Save.
