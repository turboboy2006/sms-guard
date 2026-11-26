<div align="center">

# 📨 PayamBan

**A private, offline-first SMS manager for Android — spam filtering that never leaves your phone.**

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](#-requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Material 3](https://img.shields.io/badge/UI-Material%203-1D4ED8)](#-features)
[![No Cloud](https://img.shields.io/badge/AI%20%C2%B7%20Cloud%20%C2%B7%20Trackers-none-16A34A)](#-privacy-first)
[![Build](https://github.com/turboboy2006/sms-guard/actions/workflows/build.yml/badge.svg)](https://github.com/turboboy2006/sms-guard/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**English** · [فارسی](README.fa.md)

⭐ **If this project is useful to you, a star means a lot — it helps others find it.**

</div>

---

## ✨ Why PayamBan?

Every SMS app asks for the same permissions, but most of them ship your messages to a server. Ad-supported "caller ID" apps are worse: they upload your inbox to build their database.

**PayamBan takes the opposite approach.** Spam detection, sender intelligence, campaign clustering and learning from your corrections all happen **on the device**, in plain Kotlin, with **no network call at all**. No ads, no trackers, no analytics, no cloud account.

| | |
|---|---|
| 🔒 **Nothing leaves the phone** | No server, no telemetry, no third-party SDKs. The `offline` build does not even request the `INTERNET` permission. |
| 🧠 **Explainable filtering** | Every decision ships with a score and human-readable reasons (`link · urgency · bulk opt-out`) — never a black box. |
| 🧩 **On-device learning** | Correcting one message teaches the app your rules, per sender. |
| 🚫 **Campaigns, not just messages** | A 64-bit SimHash clusters near-identical bulk messages, so one decision covers the whole campaign. |
| 🎨 **Material 3, RTL-first** | Built for Persian from the ground up: Vazirmatn typeface, correct bidi handling, light/dark themes, adjustable font scale. |
| 🗂️ **A real message trash** | Deleted messages move to a restorable trash instead of vanishing forever. |

---

## 🚀 Features

### Inbox & conversations
- Conversation list with **pinned**, **unread**, **archived** and **trash** folders
- Category chips — Contacts · Banking · OTP · Notifications · Promotions · Spam
- Full-text search across the whole provider database with debounced live results
- Long-press to select, then **range selection**; copy, share, report spam or delete from a compact action bar
- Swipe actions, per-sender colors and avatars, day separators, delayed-send composer

### Filtering & intelligence
- Weighted, explainable signal engine: links, urgency, money lures, opt-out headers, obfuscation, brand impersonation, risky TLDs, IP links, punycode…
- Persian-aware normalizer — Arabic → Persian letters, kashida, ZWNJ, Arabic/Persian digits — the exact tricks bulk senders use to dodge filters
- Sender profiles and learned weights remember what you correct
- Brand catalog for Iranian banks, telecom operators, shops and couriers (with typosquat detection)
- URL and phone-number intelligence, entirely offline

### Privacy & control
- Per-sender policies: *always analyze*, *never analyze*, *block*, *trust*
- Rule builder (sender / keyword / regex) with a live "how many existing messages match" preview
- Encrypted local storage for sensitive values (AES/GCM on the Android Keystore)
- Settings export/import — no account, no sign-in, no sync

---

## 📦 Install

### Option 1 — Download the APK
Take the latest signed APK from the [**Releases**](../../releases/latest) page.

| Build | Network permission | Use when |
|---|---|---|
| `offline` | ❌ none | Maximum privacy — no link previews, no network code path at all. |
| `full` | ✅ `INTERNET` | Adds safe link previews for URLs inside messages. |

> **Installing outside Google Play?** Android may show a *Play Protect* warning for sideloaded SMS apps. It is a heuristic, not a verdict — see [the appeal notes](README.fa.md#-play-protect) (Persian).

### Option 2 — Build it yourself
```bash
git clone https://github.com/turboboy2006/sms-guard.git
cd sms-guard
./gradlew assembleOfflineRelease   # or: assembleFullRelease
```
Requires **JDK 17** and the Android SDK (compileSdk 34). CI builds both flavors on every push — see [`.github/workflows/build.yml`](.github/workflows/build.yml).

**Requirements:** Android 8.0 (API 26) or newer.

---

## 🏗️ Project structure

```
app/src/main/java/ir/inod/smsguard/
├── MainActivity.kt              # inbox, tabs, filtering, bulk actions
├── ConversationActivity.kt      # thread view, selection, composer
├── FolderActivity.kt            # archived / trash folders
├── TrashedMessagesActivity.kt   # restorable message trash
├── Classifier.kt                # weighted signal engine (explainable)
├── Normalizer.kt                # Persian/Arabic normalization
├── LearningStore.kt             # per-sender profiles, learned weights
├── Campaign.kt                  # SimHash campaign clustering
├── UrlIntel.kt / PhoneIntel.kt  # offline URL & number intelligence
├── SmsRepository.kt             # provider access, threaded and cached
├── Store.kt                     # settings, categories, sender policies
├── RulesActivity.kt             # rule builder + live match preview
├── ManagerActivity.kt           # sender management
└── SettingsActivity.kt          # appearance, notifications, backup
```

Architecture notes and the nine pitfalls we hit while building this are documented in [`HANDOFF.md`](HANDOFF.md) and [`UI-FIXES-PLAN.md`](UI-FIXES-PLAN.md).

---

## 🔐 Privacy first

- **No `INTERNET` permission in the `offline` build** — enforced by the operating system, not by a promise.
- **No analytics, no crash reporting, no ads, no third-party SDKs.**
- **No account.** Nothing to sign in to and nothing to sync.
- Message contents are read only to classify them, and stay in the app's private storage.

---

## 🗺️ Roadmap

- [x] Explainable spam engine with on-device learning
- [x] Campaign clustering (SimHash)
- [x] Material 3 redesign, Persian-first typography, RTL/LTR correctness
- [x] Restorable trash, range selection, action bar
- [x] Offline / full build flavors
- [ ] Classifier test suite with a golden corpus
- [ ] Localization: English, Arabic
- [ ] Tablet and foldable layouts

---

## 🤝 Contributing

Issues and pull requests are welcome. Good first steps:

1. Open an issue describing the **message pattern** you think is misclassified — mask phone numbers and codes.
2. Run the app, correct a few messages, then export your learned rules from *Settings → Backup*.
3. Send a PR that adds a signal, a brand entry or a translation.

Please keep the engine **offline and explainable**: every new signal must add a visible reason string.

---

## ⭐ Support the project

If PayamBan saves you from a few spam messages a day:

- **Star this repository** — the simplest way to support it.
- Report false positives or negatives with a masked sample.
- Share it with someone who is tired of ad-filled SMS apps.

<div align="center">

**Made with care for Persian users.**

[![Star this repo](https://img.shields.io/github/stars/turboboy2006/sms-guard?style=social)](https://github.com/turboboy2006/sms-guard/stargazers)

</div>

---

## 📄 License

MIT — see [LICENSE](LICENSE).
