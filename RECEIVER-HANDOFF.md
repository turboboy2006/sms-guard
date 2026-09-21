# تحویل به نفر بعدی — چه دسترسی‌هایی لازم است و چطور APK بدهد

> این فایل را همراه `HANDOFF.md` به نفر بعدی بده. `HANDOFF.md` وضعیت فنی پروژه است،
> این فایل فقط «دسترسی‌ها» و «چطور push و APK» است.

---

## ۱) چیزهایی که باید دسترسی‌اش را داشته باشد

| مورد | مقدار | چطور بگیرد |
|---|---|---|
| **مخزن گیت‌هاب** | `https://github.com/turboboy2006/sms-guard` — **عمومی**، برنچ `main` | چون عمومی است، برای **خواندن** هیچ دسترسی‌ای لازم نیست. برای **نوشتن** باید Collaborator شود (پایین) |
| **دسترسی نوشتن روی مخزن** | نقش `Write` | صاحب مخزن (`turboboy2006`) → Settings → Collaborators → Add people → نام کاربری نفر بعدی → نقش **Write** → دعوت را در ایمیل/گیت‌هاب قبول کند |
| **اکانت گیت‌هاب خودش** | — | اگر ندارد، بسازد. برای push باید یکی از این دو را داشته باشد: **Personal Access Token** با اسکوپ `repo` (+ `workflow` اگر خواست ورک‌فلوی جدید بفرستد)، یا SSH key |
| **پوشه پروژه** | `C:\xamp\htdocs\inod\sms-guard` (در همین سیستم) | کل پوشه را کپی کند/کلون کند. `.git` داخلش هست، پس `git clone` لازم نیست — ولی کلون تازه هم کار می‌کند |
| **JDK و Android SDK** | **لازم نیست** | بیلد در GitHub Actions انجام می‌شود |
| **adb** (برای نصب روی گوشی) | `C:\Program Files (x86)\ADB and Fastboot++\adb.exe` | روی همین سیستم نصب است. باید در PATH باشد یا با مسیر کامل صدا زده شود |
| **گوشی برای تست** | — | USB debugging روشن. **هیچ‌کدام از تغییرات تا امروز روی دستگاه تست نشده** — این اولین کار اوست |
| **سیاست فایل ابزار** | پروژه **بیرون** از ورک‌اسپیس (`H:\deepseek\nodsrv`) است | ابزار باید یک‌بار با `sandbox_permissions` تأیید بگیرد، وگرنه نوشتن در `C:\...` رد می‌شود |

### توکن در همین سیستم
`git config credential.helper` روی `manager` است و توکن گیت‌هاب برای `github.com` ذخیره شده.
نفر بعدی روی **همین سیستم** چیزی لازم ندارد؛ روی سیستم خودش باید توکن خودش را بگذارد:

```powershell
git config --global credential.helper manager     # یا: store
# اولین push، خودش نام کاربری و توکن می‌پرسد
```

**نکته‌ی امنیتی:** توکن ذخیره‌شده به اکانت `turboboy2006` است. اگر نفر بعدی روی سیستم
دیگری است، **توکن خودش** را بگذارد، نه این را. و اگر اکانت مالک عوض می‌شود، توکن قبلی
را در GitHub → Settings → Developer settings → Tokens **revoke** کند.

---

## ۲) چطور تغییرات را بفرستد (push)

```powershell
cd C:\xamp\htdocs\inod\sms-guard
git status                      # ببیند چه چیزی عوض شده
git add -A
git commit -m "پیام کامیت به انگلیسی، کوتاه و توضیح‌دار"
git push origin HEAD
```

هر push روی `main` خودش بیلد را در Actions شروع می‌کند (`.github/workflows/build.yml`).

**دو تله که وقت می‌گیرد:**
1. `git push` روی این سیستم **`[exit code: 1]` می‌دهد ولی موفق است** — چون گیت روی
   stderr می‌نویسد. خروجی `HEAD -> main` یعنی موفق بوده. اشتباه نکن و دوباره push نزن.
2. API گیت‌هاب **بدون توکن بعد از چند درخواست ۴۰۳** می‌دهد (`API rate limit exceeded`).
   همیشه با توکن صدا بزن:
   ```powershell
   $in = "$env:TEMP\cred-in.txt"
   [System.IO.File]::WriteAllText($in, "protocol=https`nhost=github.com`n`n", (New-Object System.Text.ASCIIEncoding))
   $tok = ((cmd /c "git credential fill < `"$in`"" 2>&1) | Where-Object { $_ -like 'password=*' }) -replace '^password=',''
   $run = (curl.exe -s -H "Authorization: Bearer $tok" -H "Accept: application/vnd.github+json" `
     "https://api.github.com/repos/turboboy2006/sms-guard/actions/runs?per_page=1" | ConvertFrom-Json).workflow_runs[0]
   "$($run.id) $($run.status) $($run.conclusion) $($run.head_sha)"
   ```

---

## ۳) چطور APK را بگیرد — سه راه

### راه الف) سایت گیت‌هاب (ساده‌ترین، بدون توکن)
1. `https://github.com/turboboy2006/sms-guard/actions`
2. آخرین رانِ **Build APK** با تیک سبز
3. پایین صفحه → **Artifacts** → دانلود **sms-guard-debug-apk**
4. zip را باز کن → `app-debug.apk`

### راه ب) با توکن، از خط فرمان
```powershell
$runId = 35588299313     # یا id آخرین ران از دستور بالا
$login = "turboboy2006"
$zip = "$env:TEMP\apk.zip"
curl.exe -sL -H "Authorization: Bearer $tok" -o $zip `
  "https://api.github.com/repos/$login/sms-guard/actions/artifacts/<ARTIFACT_ID>/zip"
# ARTIFACT_ID را از این بگیر:
curl.exe -s -H "Authorization: Bearer $tok" -H "Accept: application/vnd.github+json" `
  "https://api.github.com/repos/$login/sms-guard/actions/runs/$runId/artifacts"
Expand-Archive $zip -DestinationPath "$env:TEMP\apk" -Force
```

### راه ج) نصب مستقیم روی گوشی از همین سیستم
```powershell
& 'C:\Program Files (x86)\ADB and Fastboot++\adb.exe' devices
& 'C:\Program Files (x86)\ADB and Fastboot++\adb.exe' install -r `
  'C:\xamp\htdocs\inod\sms-guard\dist\app-debug.apk'
```

### یک نکته‌ی مهم درباره‌ی APK
پوشه‌ی `dist/` و همه‌ی `*.apk` در `.gitignore` هستند، پس **APK داخل مخزن نیست**.
نسخه‌ی آماده روی همین سیستم اینجاست:
`C:\xamp\htdocs\inod\sms-guard\dist\app-debug.apk`
اگر نفر بعدی روی سیستم دیگری است، باید از Artifacts گیت‌هاب بگیرد (راه الف/ب).

**امضا:** APK دیباگ است و با **debug keystore** امضا می‌شود — که در CI هر بار تازه ساخته
می‌شود. یعنی نصب روی نسخه‌ی قبلی **فقط اگر همان سیستم بیلد کرده باشد** به‌عنوان آپدیت
نصب می‌شود؛ وگرنه باید نسخه‌ی قبلی را حذف کند (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
برای انتشار واقعی باید یک keystore ثابت بسازد و در Secrets گیت‌هاب بگذارد (کار نشده).

---

## ۴) اگر بیلد سرخ شد — چطور خطا را ببیند

```powershell
$jobs = (curl.exe -s -H "Authorization: Bearer $tok" -H "Accept: application/vnd.github+json" `
  "https://api.github.com/repos/turboboy2006/sms-guard/actions/runs/$runId/jobs") | ConvertFrom-Json
curl.exe -sL -H "Authorization: Bearer $tok" -o "$env:TEMP\log.txt" `
  "https://api.github.com/repos/turboboy2006/sms-guard/actions/jobs/$($jobs.jobs[0].id)/logs"
Select-String -Path "$env:TEMP\log.txt" -Pattern 'e: file:///'
```

هر خط `e: file:///...` یک خطای کامپایل با شماره‌ی خط است. `w:` فقط اخطار است.

---

## ۵) چیزهایی که باید به او هشدار بدهی

1. **هیچ‌چیز روی گوشی تست نشده.** فقط کامپایل و بررسی ایستا. اولین کارش: نصب و بررسی
   صندوق پیام، تب مخاطبین، و ارسال پیام.
2. **متن‌های فارسی را با ابزار اشتباه ننویسد.** `Set-Content`/`Out-File` در PowerShell
   کاراکترهای یونیکد را خراب می‌کنند. باید:
   ```powershell
   [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
   ```
   و بعدش با اسکن کاراکترهای بالای `U+007E` مطمئن شود چیزی خراب نشده. یک بار `—`
   به `â€"` تبدیل شد.
3. **`HANDOFF.md` بخش «نکات فنی» را بخواند** — ۲۰ اشتباه تکراری آنجاست (هر کدام یک
   بیلد سوزانده) و «کارهای باقی‌مانده» با اولویت.
4. **مجوزهای اندروید:** اپ باید **اپ پیامک پیش‌فرض** شود وگرنه پیامک دریافت نمی‌کند؛
   `READ_SMS`، `RECEIVE_SMS`، `SEND_SMS`، `READ_CONTACTS` و (روی اندروید ۱۳+)
   `POST_NOTIFICATIONS` لازم است. `RECEIVE_BOOT_COMPLETED` هم برای جاب پس‌زمینه هست.
5. **مخزن عمومی است.** هیچ کلید API، توکن یا اطلاعات شخصی داخلش نگذارد. کلید هوش
   مصنوعی در اپ با Android Keystore رمز می‌شود و در مخزن نیست.

---

## ۶) وضعیت فعلی در یک خط

آخرین کامیت `5b7174e`، آخرین بیلد سبز `run 35588299313`، APK آماده در
`dist\app-debug.apk` (۶ مگابایت، نسخه‌ی ۱٫۰، `versionCode 1`).
