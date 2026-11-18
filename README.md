# Mail Triage — Android app

Pulls email from Gmail, Hotmail/Outlook, AOL, Yahoo (or any IMAP account), sorts each
one into **Important / Routine / Noise**, gives it a priority (1 = act today … 5 = ignore),
and writes a one-line summary with Claude.

It only **reads** your mail. It never deletes, moves or marks anything on the server.

---

## 1. Build and install (one time, ~20 min)

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Unzip this folder, then in Android Studio: **File → Open** → pick the `MailTriage` folder.
   Let it finish "Gradle sync" (it downloads what it needs the first time).
3. On your Galaxy S23: **Settings → About phone → Software information** → tap
   **Build number** 7 times. Then **Settings → Developer options → USB debugging** → on.
4. Plug the phone into the PC, accept the "Allow USB debugging" prompt on the phone.
5. In Android Studio, pick your phone in the device dropdown and press **▶ Run**.
   The app "Mail Triage" is now installed and stays on the phone.

## 2. Claude API key (for summaries)

1. Go to https://console.anthropic.com → sign up → **Billing** → add a few dollars of credit.
2. **API Keys → Create key**, copy it.
3. In the app: **Settings → Claude API key** → paste → **Save**.

Cost: the app only sends each email once (results are cached), using the low-cost Haiku
model. A few hundred emails a month should cost about $1–3. You can set a monthly spend
limit in the console. Without a key, the app still works using built-in rules and shows a
text preview instead of a summary.

## 3. Add accounts

**Gmail** — needs an app password:
1. https://myaccount.google.com/security → turn on **2-Step Verification**.
2. https://myaccount.google.com/apppasswords → create one named "Mail Triage".
3. In the app: **Accounts → Gmail** → email + the 16-letter app password → **Add account**.

**AOL** — https://login.aol.com/account/security → **Generate app password**, then add as AOL.

**Yahoo** — https://login.yahoo.com/account/security → **Generate app password**, then add as Yahoo.

**Hotmail / Outlook.com** — Microsoft requires a sign-in page instead of a password, so
you register the app once with Microsoft (free):
1. Go to https://portal.azure.com (sign in with your Hotmail account) → search
   **App registrations** → **New registration**.
2. Name: `Mail Triage`. Supported accounts: **Personal Microsoft accounts only**. Register.
3. **Authentication → Add a platform → Mobile and desktop applications** →
   custom redirect URI: `com.alexdiaz.mailtriage://oauth` → **Configure**.
4. **API permissions → Add a permission → APIs my organization uses** → search
   **Office 365 Exchange Online** → Delegated → tick **IMAP.AccessAsUser.All** → Add.
   (If it doesn't show up, the app still asks for it at sign-in. Just continue.)
5. **Overview** → copy the **Application (client) ID**.
6. In the app: **Settings → Azure client ID** → paste → Save.
   Then **Accounts → Hotmail / Outlook** → type your address → **Sign in with Microsoft**.

## 4. Use it

Tap **Refresh**. The app checks the last 3 days of each inbox (change this in Settings),
summarizes anything new, and sorts by priority:

- 🔴 1 act today · 🟠 2 this week · 🟡 3 worth reading · 🔵 4 FYI · ⚪ 5 ignore

Tap a card to expand it. To fix a wrong category, use **Move to …**.

## Where things live

| File | What it does |
|---|---|
| `MailFetcher.kt` | Reads recent INBOX mail over IMAP (read-only) |
| `Triage.kt` | Claude summaries + priority; offline rule fallback (edit the word lists to tune) |
| `OutlookAuth.kt` | Microsoft sign-in for Hotmail/Outlook |
| `SecureStore.kt` | Encrypted storage for passwords, tokens, API key, cache |
| `MainViewModel.kt` | Refresh logic, caching so each email is summarized only once |
| `MainActivity.kt` | Screens: Inbox tabs, Accounts, Settings |

Ideas for later: automatic background checks with a notification for priority-1 mail,
an Important-only home-screen widget, a "mark as read on server" button.
