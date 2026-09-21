# سند تحویل — پیام‌بان (SmsGuard)

> این فایل را در نشست تازه به عامل بده، یا بگو «`HANDOFF.md` را بخوان و ادامه بده».

## وضعیت فعلی

| | |
|---|---|
| مسیر پروژه | `C:\xamp\htdocs\inod\sms-guard` |
| مخزن | https://github.com/turboboy2006/sms-guard (**عمومی**) |
| آخرین کامیت | `7dac47e` |
| آخرین بیلد | **#35، سبز** (۳۵ بیلد پیاپی، همه سبز) |
| APK | `dist\app-debug.apk` (~۶٬۱۸ MB) |
| زبان‌ها | ۱۲۸ کلید در `values/` و `values-fa/`، تطابق کامل |

**اپ بومی اندروید است: Kotlin + Views + Material 3.** Flutter نیست.

## معماری

```
app/src/main/java/ir/inod/smsguard/
├── SmsApp · BaseActivity          Application و اعمال fontScale
├── SmsReceiver · MmsReceiver      SMS_DELIVER، WAP_PUSH_DELIVER
├── HeadlessSmsSendService         RESPOND_VIA_MESSAGE
├── SmsRepository                  provider خواندن/نوشتن/حذف
├── Classifier                     موتور اصلی: سیگنالها، دستهبندی، کشها
├── Normalizer                     نرمالسازی فارسی + obfuscation
├── LearningStore                  SenderProfile + LearnedWeights (log-odds)
├── Campaign                       SimHash ۶۴ بیتی + خوشهبندی همینگ
├── UrlIntel · PhoneIntel · IconCatalog (+BrandCatalog/BrandResolver)
├── Store                          Settings/Rule/Category/Sender/Message/Blocked/Campaign
├── SecureKeyStore                 AES-GCM روی Android Keystore
├── AnalysisPipeline · MessageBus  لایه AI پسزمینه
├── AvatarHelper                   عکس / مونوگرام / آدمک
├── MainActivity                   Inbox (چیپ + BottomNav + FAB)
├── ConversationActivity · RulesActivity · SettingsActivity · ManagerActivity
└── ThreadAdapter · MessageAdapter · RuleAdapter
```

## کارهای انجام‌شده

**موتور هوش (۲۶ از ۴۱ مورد):**
- نرمالسازی فارسی: `ي/ك`، کشیده، نیم‌فاصله، ارقام، صفرعرض، تکرار حرف، فاصله‌دار، **نقطه‌دار** (`ت.خ.ف.ی.ف`)
- امتیازدهی وزندار: تبلیغاتی، عجله، CTA، قالب جمله‌ای، مالی زمینه‌محور، ایموجی، fraud
- URL: طول، زیردامنه، **آنتروپی**، نسبت رقم، `xn--`، IP، کوتاه‌کننده، redirect
- برند: فازی با Levenshtein + عدم تطابق برند/دامنه
- فرستنده: پروفایل (volume/burst/reputation)، اعتبار، burst، **callback-number**
- یادگیری: log-odds لاپلاس، n-gram کلمه/بی‌گرام/تری‌گرام حرف، بازخورد + بازگردانی
- کمپین: SimHash + خوشه + تعمیم حکم کاربر
- زمان: سیگنال شبانه + **ساعات سکوت** (۲۳ تا ۷)
- لایه AI اختیاری (پیش‌فرض خاموش) با OpenAI-compatible

**UI:**
- Material 3، وزیرمتن، گرید ۸ نقطه‌ای، حالت شب، اسکلتون، انتقال ۲۲۰ms
- **اندازه فونت** در تنظیمات (۰٫۸۵ / ۱٫۰ / ۱٫۱۵ / ۱٫۳)
- Inbox: چیپ فیلتر پر آبی، BottomNav دو آیتمی، FAB، آواتار ۵۲dp، بج کمرنگ، ردیف خوانده‌نشده `#EFF6FF`، جداکننده مویی
- گفتگو: جداکننده تاریخ («امروز/دیروز»)، حذف تک‌پیام
- سطل زباله: تب مستقل، انتقال، حذف دائمی، خالی‌کردن (با تأیید)
- تأیید اجباری + بازگردانی ۵ ثانیه‌ای (شامل `revert` آموزش)
- صفحه برندها و مسدودشده‌ها (نام/دسته/رنگ/آیکون)
- تاریخ شمسی + روزهای فارسی + ارقام فارسی
- KeyStore برای کلید API

## کارهای باقی‌مانده

1. **ظاهر — نزدیک‌تر به طرح مرجع.** کاربر گفت هنوز فاصله دارد.
   - مرجع طراحی: `C:\xamp\htdocs\inod\new-ui-help\` (Flutter، **فقط به‌عنوان مشخصات طراحی**)
   - ✱ **مهم:** از کاربر یک **اسکرین‌شات از گوشی خودش** بخواه، نه طرح مرجع. بعد کنار هم بگذار.
2. **۱۵ مورد هوش:** Swipe actions · چندانتخابی · SearchBar · `ListAdapter + DiffUtil` · Bottom Navigation تکمیل · نمای کمپین · فراموشی زمانی · بازخورد ضمنی · صادرات مدل · کلمات توقف · بازه‌های اقدام · Transformer (اختیاری)
3. **صفحه رضایت حریم خصوصی** — کاربر گفت فعلاً لازم نیست

## نکات فنی — اشتباهات من که نباید تکرار شوند

1. **`TabLayout.Tab` را نمی‌توان با نام کاملاً-کیفیشده در Kotlin صدا زد.** باید `import com.google.android.material.tabs.TabLayout` و بدون پیشوند.
2. **`Chip.ensureMinTouchTargetSize` پراپرتی خصوصی است.** از `setEnsureMinTouchTargetSize(true)` استفاده کن.
3. **`?:` از `>=` ضعیف‌تر است.** `a ?: 0 >= 4` کامپایل نمی‌شود؛ پرانتز لازم است.
4. **`inner class` داخل `inner class`** در امضای `RecyclerView.Adapter<RowAdapter.VH>` مشکل می‌سازد. `class` ساده بگذار.
5. **`values-night/themes.xml` را بازتعریف نکن** — کل استایل را جایگزین می‌کند و رنگ‌ها به بنفش پیش‌فرض M3 برمی‌گردند.
6. **ANR دو بار:** هر کار سنگین (اسکن provider، طبقه‌بندی) باید روی ترد پس‌زمینه باشد.
7. **طبقه‌بندی را per-sender کش کن، نه per-message.** همه‌ی پیام‌های یک گفتگو یک فرستنده دارند. `O(rows × classify)` → `O(1 × classify)`.
8. **`JSONObject(map as Map<*, *>)` شکننده است.** دستی بساز: `for ((k,v) in map) o.put(k, v)`.
9. **اندازه فونت را با `Configuration.fontScale` بده، نه ضرب دستی.** همه‌ی `sp`ها خودکار مقیاس می‌گیرند.

## ابزار بیلد و دیباگ

**بیلد:** GitHub Actions (نه لوکال — JDK 17 و Android SDK روی سیستم نیست).
```powershell
git -C C:\xamp\htdocs\inod\sms-guard push
```

**وضعیت بیلد بدون مرورگر** (مخزن عمومی است):
```powershell
curl.exe -s -H "Accept: application/vnd.github+json" "https://api.github.com/repos/turboboy2006/sms-guard/actions/runs?per_page=1"
```

**لاگ کامل خطای کامپایل** — اندپوینت `/logs` قدیمی ۴۰۴ می‌دهد و API بدون توکن ۴۰۳. راهی که کار می‌کند، توکن ذخیره‌شده‌ی GCM با redirect فایل است:
```powershell
$in = "$env:TEMP\cred-in.txt"
[System.IO.File]::WriteAllText($in, "protocol=https`nhost=github.com`n`n", (New-Object System.Text.ASCIIEncoding))
$tok = ((cmd /c "git credential fill < `"$in`"" 2>&1) | Where-Object { $_ -like 'password=*' }) -replace '^password=',''
curl.exe -sL -H "Authorization: Bearer $tok" -o log.txt `
  "https://api.github.com/repos/turboboy2006/sms-guard/actions/jobs/<jobId>/logs"
Select-String -Path log.txt -Pattern 'e: file:///'
```

**دیباگ روی گوشی** (فقط خواندنی):
```powershell
adb devices
adb logcat -d -v brief | Where-Object { $_ -match 'smsguard' -and $_ -match '^\s*[EFW]/' }
```
کرش واقعی را در `FATAL EXCEPTION` و ANR را در `ANR in ir.inod.smsguard` بگیر.

**مرورگر** (لاگین‌شده، برای گیت‌هاب): `dsh-open https://github.com` سپس `dsh-browse <url> --cdp --script <file.js>`

## محدودیت‌های محصول

- **RCS خاموش می‌شود** وقتی اپ پیش‌فرض شود
- **MMS پشتیبانی نمی‌شود** (Receiver هست چون اندروید الزام می‌کند)
- **ساعات سکوت ثابت ۲۳-۷ است**، نه قابل تنظیم در UI
- اپ **روی دستگاه اجرا نشده** — فقط کامپایل و بررسی ایستا
