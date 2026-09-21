# سند تحویل — پیام‌بان (SmsGuard)

> این فایل را در نشست تازه به عامل بده، یا بگو «`HANDOFF.md` را بخوان و ادامه بده».

## وضعیت فعلی

| | |
|---|---|
| مسیر پروژه | `C:\xamp\htdocs\inod\sms-guard` |
| مخزن | https://github.com/turboboy2006/sms-guard (**عمومی**) |
| آخرین کامیت | `2ba22fd` — «fix(ui): View has no maxHeight, and bindBadge returns whether it drew» |
| آخرین بیلد | **سبز** (`run 35583420093`) |
| APK | `dist\app-debug.apk` (~۵٫۹۷ MB، از همان بیلد سبز) |
| زبان‌ها | ۱۸۰ کلید در `values/` و `values-fa/`، تطابق کامل |

**اپ بومی اندروید است: Kotlin + Views + Material 3.** Flutter نیست.
دسترسی به مسیر پروژه: سیاست فایل این نشست `danger-full-access` است و پوشه پروژه بیرون از
ورک‌اسپیس (`H:\deepseek\nodsrv`) قرار دارد؛ اگر policy روی `workspace-write` برگردد،
نوشتن در `C:\xamp\...` رد می‌شود و باید یک‌بار با `sandbox_permissions` تأیید گرفته شود.

## معماری

```
app/src/main/java/ir/inod/smsguard/
├── SmsApp · BaseActivity          Application (کانال + observer مخاطبین) و fontScale
├── SmsReceiver · MmsReceiver      SMS_DELIVER، WAP_PUSH_DELIVER
├── HeadlessSmsSendService         RESPOND_VIA_MESSAGE
├── SmsRepository                  provider خواندن/نوشتن/حذف + کش صندوق
├── ThreadCache                    کش ماندگار صندوق (TSV در filesDir)
├── ContactsIndex                  کل دفترچه‌ی مخاطبین یک‌بار در حافظه
├── Classifier                     موتور اصلی: سیگنال‌ها، دسته‌بندی، کش‌ها، riskLabel
├── Normalizer                     نرمال‌سازی فارسی + obfuscation
├── LearningStore                  SenderProfile + LearnedWeights (log-odds)
├── Campaign                       SimHash ۶۴ بیتی + خوشه‌بندی همینگ
├── UrlIntel · PhoneIntel · IconCatalog (+BrandCatalog/BrandResolver)
├── Store                          Settings/Rule/Category/Sender/Message/Blocked
│                                  + ThemePrefs/RowStyle/MessageStyle/RowLayout
├── SecureKeyStore                 AES-GCM روی Android Keystore
├── AnalysisPipeline · MessageBus  لایه AI پس‌زمینه
├── AvatarHelper · RowStyler · MessageStyler   ظاهر ردیف و حباب (کد، نه XML)
├── MainActivity                   Inbox (چیپ + BottomNav + FAB + Search + Observer)
├── ConversationActivity · RulesActivity · SettingsActivity
├── AppearanceActivity (+AppearancePreviewView)  تنظیمات ظاهری با پیش‌نمایش زنده
├── ManagerActivity
└── ThreadAdapter · MessageAdapter · RuleAdapter
```

## چه چیزی در نشست قبل انجام شد (کامیت‌های `0aac8a0` … `7a3f6f0`)

**۱) سرعت لود صندوق**
- `ThreadCache`: فهرست گفتگوها در `filesDir/inbox-cache.tsv` ذخیره می‌شود (یک خط برای هر
  گفتگو، با U+001F به‌عنوان جداکننده). در `MainActivity.onCreate` **قبل از** هر کوئری
  provider از دیسک خوانده و همان لحظه رسم می‌شود، بعد sync در پس‌زمینه انجام می‌شود.
- `SmsRepository.loadThreads` تشخیص می‌دهد که «جدیدترین پیام provider همان است که در کش
  داریم»؛ در آن حالت هیچ دسته‌بندی‌ای دوباره محاسبه نمی‌شود و فقط یک گذر cursor هزینه دارد.
- `SmsReceiver` بعد از ذخیره‌ی پیام جدید، همان کش را patch می‌کند (`patchCacheForNewMessage`)
  پس حتی اگر اپ باز نشود، اجرای بعدی درست است.
- `ContentObserver` روی `Telephony.Sms.CONTENT_URI` با debounce ۴۰۰ms → پیام تازه بدون
  رفرش دستی می‌آید.
- `ThreadAdapter.merge` به‌جای `notifyDataSetChanged` فقط تغییرات را اعلام می‌کند
  (اسکرول و فلش لیست حفظ می‌شود).
- گلوگاه اصلی کندی، `PhoneLookup` بود: هر ردیف **دو** کوئری provider می‌زد (نام + عکس).
  حالا `ContactsIndex` کل دفترچه را یک‌بار می‌خواند و تطبیق را در حافظه انجام می‌دهد.
- گزینه‌ی «تازه‌سازی از گوشی» در منوی سرریز، کش را پاک و دوباره می‌سازد.

**۲) اندازه فونت جدا برای هر بخش**
- `ThemePrefs.listFontScale` (فهرست پیام‌ها) و `ThemePrefs.messageFontScale` (متن گفتگو)
  مستقل‌اند و در `ThreadAdapter` / `MessageAdapter` روی `sp` ضرب می‌شوند.
- تغییرات بی‌درنگ اعمال می‌شوند: `MainActivity.onResume` اسنپ‌شات ظاهر را مقایسه می‌کند و
  اگر عوض شده باشد همان‌جا `applyLayout` می‌زند؛ `BaseActivity` هم برای تغییر مقیاس کلی
  فونت از `SettingsStore.revision` استفاده می‌کند و `recreate()` می‌کند.

**۳) بخش «ظاهر»**
- `AppearanceActivity`: پیش‌نمایش زنده بالای صفحه (همان `RowStyler` که لیست واقعی استفاده
  می‌کند، پس پیش‌نمایش از نتیجه جدا نمی‌افتد).
- ۱۰ نوع پس‌زمینه‌ی ردیف: کلاسیک، کارتی، تخت، نوار رنگی، حبابی، فشرده، ملایم، قاب‌دار،
  راه‌راه، قرصی. ۵ نوع پس‌زمینه‌ی حباب پیام.
- اسلایدرها: فاصله‌ی عمودی ردیف، فاصله‌ی بین ردیف‌ها، حاشیه‌ی کنار، فضای خالی بالا/پایین
  فهرست، فاصله‌ی بین پیام‌ها، گردی گوشه‌ی حباب. به‌علاوه دو کلید نمایش خط جداکننده و چیپ‌ها.
- `SettingsActivity` به چهار گروه دسته‌بندی شد: تحلیل پیام‌ها / ظاهر / عمومی / مدیریت.

**۴) دسته‌ی «مخاطبین»**
- چیپ اول ردیف فیلترها؛ فقط گفتگوهایی که فرستنده‌شان در دفترچه‌ی گوشی هست.

**۵) ظاهر Inbox طبق مرجع**
- ردیف‌ها: آواتار ۴۸dp، نام ۱۵sp بولد، پیش‌نمایش ۱۴sp دو خط، زمان ۱۱٫۵sp، جداکننده‌ی مویی
  با تورفتگی ۷۶dp (تراز با ستون متن)، ارتفاع طبیعی ردیف (قبلاً `row_min_height=112dp` بود
  که ردیف‌ها را بی‌دلیل بلند می‌کرد).
- فرستنده‌های شخصی: دایره‌ی پاستلی با حرف اول به رنگ تیره (قابل تشخیص و آرام)، برندها با
  آیکون و رنگ خودشان.
- چیپ‌های فیلتر آیکون‌دار شدند.
- گفتگو: نوار بالا سفید با تیتر تیره، دکمه‌ی ارسال دایره‌ای، حباب‌های ۱۴dp گرد.
- حالت خالی: آیکون + متن به‌جای یک TextView خالی.

**۶) سخت‌سازی پس از بازبینی (کامیت `7a3f6f0`)**- `ThreadCache.update` عملیات read-modify-write را اتمیک کرد؛ قبلاً پیامی که وسط یک sync
  می‌رسید می‌توانست از کش بیفتد.
- خواندن کش از `onCreate` به worker منتقل شد؛ به‌علاوه حذف گفتگو، read کردن thread،
  resolve کردن threadId و ارسال پیام همه از ترد اصلی خارج شدند.
- نام مخاطب در مسیر UI هرگز دفترچه را نمی‌سازد (`ContactsIndex.readyEntryFor`)، پس
  ویرایش یک مخاطب باعث فریز فریم نمی‌شود.
- `Math.floorMod` جای `abs()` در هش پالت (باگ `Int.MIN_VALUE`)، و `jobFinished(false)`.

**۷) ساعات سکوت اختیاری**
- `SettingsStore.quietHoursEnabled` پیش‌فرض `false`، به‌همراه `quietFrom`/`quietTo`.
- کارت «ساعات سکوت» در تنظیمات: کلید + توضیح کامل + انتخاب ساعت شروع و پایان
  (فقط وقتی روشن باشد دیده می‌شوند) و خلاصه‌ی «سکوت از ۲۳:۰۰ تا ۰۷:۰۰».
- `QuietHours.shouldSuppress` تنها مرجع تصمیم است و `SmsReceiver` همان را صدا می‌زند؛
  بازه‌ی غیرچرخشی (مثلاً ۱ تا ۵) هم پشتیبانی می‌شود.

**۸) دو باگ گزارش‌شده‌ی کاربر روی گوشی**
- **دسته‌ی «مخاطبین» خالی بود:** دفترچه قبل از گرفتن مجوز READ_CONTACTS خوانده می‌شد،
  ایندکس خالی ساخته و **۱۰ دقیقه کش** می‌شد؛ بعد از دادن مجوز هم همان ایندکس خالی جواب
  می‌داد. حالا ایندکس خالی هرگز کش نمی‌شود و `MainActivity` قبل از هر sync دوباره
  `ContactsIndex.ensure` را صدا می‌زند.
- **آیکن مداد صفحه‌ی سفید می‌آورد:** `ConversationActivity` بدون گیرنده باز می‌شد و چیزی
  برای نمایش نداشت. حالا `RecipientPicker` (باتم‌شیت با جست‌وجو روی مخاطبین + گفتگوهای
  اخیر + امکان ارسال به شماره‌ی تایپ‌شده) اول باز می‌شود؛ خود صفحه‌ی گفتگو هم حالت خالی
  توضیح‌دار با دکمه‌ی «انتخاب گیرنده» دارد.

## کارهای باقی‌مانده

0. **ادامه‌ی یکپارچگی بصری (بازخورد کاربر، کامیت `2ba22fd` نیمی از آن را برد).**
   انجام‌شده: پالت واحد (`#F7F9FC` / `#667085` / Status bar دقیقاً `#1D4ED8`)،
   چیپ‌های ۴۶dp با ۱۸dp پدینگ و فاصله‌ی ۸dp (بدون چیپ مخاطبین و زباله)،
   BottomNav سفید ۷۲dp با Pill آبی روشن، ردیف ۸۸..۱۰۸dp، ستون زمان سمت چپ فیزیکی،
   رندر LTR برای شماره/کد/لینک/مبلغ، آواتار بدون قرمز/نارنجی، Badge بدون بریدگی.
   باقی‌مانده: هم‌سان‌سازی صفحه‌ی گفتگو و تنظیمات/ظاهر با همین توکن‌ها.
   **پرسش باز:** «زمان در ستون چپ» فیزیکی پیاده شد؛ اگر منظور RTL (راست) بوده،
   فقط `layoutDirection` ریشه‌ی `item_thread.xml` باید عوض شود.
1. **ظاهر — ادامه‌ی نزدیک‌شدن به مرجع.** مرجع طراحی `C:\xamp\htdocs\inod\new-ui-help\`
   (Flutter، فقط مشخصات طراحی) و اسکرین‌شات‌های خود کاربر.
2. **۱۵ مورد هوش:** Swipe actions · چندانتخابی · `ListAdapter + DiffUtil` رسمی ·
   نمای کمپین · فراموشی زمانی · بازخورد ضمنی · صادرات مدل · کلمات توقف · بازه‌های اقدام ·
   Transformer (اختیاری).
3. **کار پس‌زمینه‌ی واقعی:** الان فقط `SmsReceiver` کش را تازه می‌کند. اگر لازم شد که اپ
   حین بسته‌بودن هم AI را روی پیام‌های معلق اجرا کند، `androidx.work:work-runtime-ktx`
   اضافه شود (عمداً اضافه نشد تا وابستگی و ریسک بیلد بالا نرود).
4. **صفحه رضایت حریم خصوصی** — کاربر گفت فعلاً لازم نیست.
5. **تست روی دستگاه واقعی** — هیچ‌کدام از این تغییرات روی گوشی اجرا نشده؛ فقط کامپایل و
   بررسی ایستا. اولین کاری که روی دستگاه باید کرد: اندازه‌گیری زمان sync صندوق با
   `adb logcat | Select-String "inbox sync finished"` (فقط در build دیباگ لاگ می‌شود).

## نکات فنی — اشتباهات تکراری که نباید دوباره رخ دهند

1. **`TabLayout.Tab` را نمی‌توان با نام کاملاً-کیفیشده در Kotlin صدا زد.** باید
   `import com.google.android.material.tabs.TabLayout` و بدون پیشوند.
2. **`Chip.ensureMinTouchTargetSize` پراپرتی خصوصی است.** از `setEnsureMinTouchTargetSize(true)`.
3. **`?:` از `>=` ضعیف‌تر است.** `a ?: 0 >= 4` کامپایل نمی‌شود؛ پرانتز لازم است.
4. **`inner class` داخل `inner class`** در امضای `RecyclerView.Adapter<RowAdapter.VH>`
   مشکل می‌سازد. `class` ساده بگذار.
5. **`values-night/themes.xml` را بازتعریف نکن** — کل استایل جایگزین می‌شود و رنگ‌ها به
   بنفش پیش‌فرض M3 برمی‌گردند. فقط رنگ‌ها در `values-night/colors.xml`.
6. **ANR:** هر کار سنگین (اسکن provider، طبقه‌بندی، خواندن دفترچه‌ی مخاطبین) روی ترد
   پس‌زمینه. `ContactsIndex.ensure` عمداً داخل worker صدا زده می‌شود، نه در `onResume`.
7. **طبقه‌بندی را per-sender کش کن، نه per-message.** `O(rows × classify)` → `O(1 × classify)`.
8. **`JSONObject(map as Map<*, *>)` شکننده است.** دستی بساز: `for ((k,v) in map) o.put(k, v)`.
9. **ViewBinding به `android:id` در layout وابسته است.** در نشست قبل `textEmpty` از
   `TextView` به `LinearLayout` تغییر کرد و `setText` روی آن کامپایل نمی‌شد؛ متن به
   `textEmptyLabel` منتقل شد. هر تغییر نوع ویو، همه‌ی `binding.X`ها را چک کن.
10. **پارامتر `textDirection` روی `TextView` را با `View.TEXT_DIRECTION_RTL` ست کن**، نه با
    `android:textDirection` در XML، تا برای متن لاتین هم درست کار کند (`TextDir.apply`).
11. **`Slider` در Material، `LinearLayout` نیست.** اگر روی آن `layout_weight` بگذاری
    بی‌اثر است؛ در `activity_settings.xml` اسلایدر و مقدارش در یک `LinearLayout` افقی‌اند.
12. **پیش‌نمایش ظاهر باید از همان کد لیست بیاید** (`RowStyler`)، وگرنه بعد از هر تغییر
    ظاهر، پیش‌نمایش دروغ می‌گوید و کاربر فکر می‌کند تنظیمات کار نمی‌کند.
13. **`Chip` عضو `chipIconStartPadding` ندارد** (نه پراپرتی، نه attr). اگر پدینگ آیکون
    لازم شد: `chipStartPadding` / `iconStartPadding`. این یک خط، یک بیلد کامل را سوزاند.
14. **`Slider.setValue` استثنا می‌اندازد** اگر مقدار روی `valueFrom + k*stepSize` نباشد.
    هر مقداری که از prefs خوانده می‌شود باید در همان setter روی step گرد شود.
15. **`abs(hashCode())` غلط است**: برای `Int.MIN_VALUE` سرریز می‌کند و پالت را از محدوده
    بیرون می‌زند. `Math.floorMod(hash, size)`.
16. **کش را با `ThreadCache.update {}` تغییر بده، نه `read` + `write`.** الگوی دستی،
    آپدیت هم‌زمانِ receiver و sync را گم می‌کند (پیام بی‌صدا ناپدید می‌شود).
17. **در نوشتن فایل با `Set-Content`/`Out-File` متن فارسی و کاراکترهای یونیکد خراب می‌شوند.**
    برای فایل‌های این پروژه از `[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))`
    استفاده کن و بعدش با اسکن کاراکترهای بالای U+007E مطمئن شو چیزی خراب نشده. یک بار
    `—` به `â€"` تبدیل شد و اگر در رشته‌ی کد بود، بیلد می‌شکست.
18. **`View` پراپرتی `maxHeight` ندارد** (فقط `TextView` و `ImageView`). برای سقف ارتفاع
    یک ردیف، `layoutParams.height` را ست کن یا `ViewGroup.LayoutParams.WRAP_CONTENT`.
19. **`visibility` از نوع `Int` است، نه `Boolean`.** `view.visibility = someBooleanReturningFn()`
    کامپایل نمی‌شود؛ شرط را جدا بنویس.
20. **آیکون‌های برداری پروژه `fillColor="#FFFFFF"` دارند** و با `app:tint` /
    `chipIconTint` رنگ می‌گیرند؛ رنگ داخل خود فایل مهم نیست، اما اگر tint نگذاری سفید
    روی سفید دیده نمی‌شوند.

## ابزار بیلد و دیباگ

**بیلد:** GitHub Actions (نه لوکال — JDK 17 و Android SDK روی این سیستم نیست؛ فقط
Java 10 نصبت است و `gradle` وجود ندارد).
```powershell
git -C C:\xamp\htdocs\inod\sms-guard push
```

**وضعیت بیلد بدون مرورگر** (مخزن عمومی است):
```powershell
curl.exe -s -H "Accept: application/vnd.github+json" "https://api.github.com/repos/turboboy2006/sms-guard/actions/runs?per_page=1"
```

**گرفتن job id و لاگ:**
```powershell
# بدون توکن، API بعد از چند درخواست ۴۰۳ می‌دهد: از همان توکن GCM استفاده کن
$in = "$env:TEMP\cred-in.txt"
[System.IO.File]::WriteAllText($in, "protocol=https`nhost=github.com`n`n", (New-Object System.Text.ASCIIEncoding))
$tok = ((cmd /c "git credential fill < `"$in`"" 2>&1) | Where-Object { $_ -like 'password=*' }) -replace '^password=',''
$run = (curl.exe -s -H "Authorization: Bearer $tok" -H "Accept: application/vnd.github+json" `
  "https://api.github.com/repos/turboboy2006/sms-guard/actions/runs?per_page=1" | ConvertFrom-Json).workflow_runs[0]
$jobs = (curl.exe -s -H "Authorization: Bearer $tok" -H "Accept: application/vnd.github+json" `
  "https://api.github.com/repos/turboboy2006/sms-guard/actions/runs/$($run.id)/jobs") | ConvertFrom-Json
$jobs.jobs | Select-Object id,name,conclusion
# دانلود خودِ APK (بدون توکن ۴۰۳ می‌دهد)
$arts = (curl.exe -s -H "Authorization: Bearer $tok" -H "Accept: application/vnd.github+json" `
  "https://api.github.com/repos/turboboy2006/sms-guard/actions/runs/$($run.id)/artifacts" | ConvertFrom-Json)
curl.exe -sL -H "Authorization: Bearer $tok" -o apk.zip `
  "https://api.github.com/repos/turboboy2006/sms-guard/actions/artifacts/$($arts.artifacts[0].id)/zip"
Expand-Archive apk.zip -DestinationPath out -Force
Copy-Item out\app-debug.apk dist\app-debug.apk -Force
```

**لاگ کامل خطای کامپایل** — همان توکن بالا:
```powershell
curl.exe -sL -H "Authorization: Bearer $tok" -o log.txt `
  "https://api.github.com/repos/turboboy2006/sms-guard/actions/jobs/<jobId>/logs"
Select-String -Path log.txt -Pattern 'e: file:///'
```

**دیباگ روی گوشی** (فقط خواندنی):
```powershell
adb devices
adb logcat -d -v brief | Where-Object { $_ -match 'smsguard|SmsGuard' -and $_ -match '^\s*[EFWD]/' }
```
کرش واقعی را در `FATAL EXCEPTION`، ANR را در `ANR in ir.inod.smsguard` و زمان sync را در
`inbox sync finished in ...ms` بگیر.

**مرورگر** (لاگین‌شده، برای گیت‌هاب): `dsh-open https://github.com` سپس
`dsh-browse <url> --cdp --script <file.js>`

## محدودیت‌های محصول

- **RCS خاموش می‌شود** وقتی اپ پیش‌فرض شود
- **MMS پشتیبانی نمی‌شود** (Receiver هست چون اندروید الزام می‌کند)
- **ساعات سکوت پیش‌فرض خاموش است** و در تنظیمات روشن می‌شود (بازه‌اش هم همان‌جا تنظیم
  می‌شود، پیش‌فرض ۲۳ تا ۷). منطقش در `QuietHours` است: فقط تبلیغاتی/مشکوک/اسپم بی‌صدا
  می‌شوند؛ رمز پویا، بانکی و مخاطبین همیشه اعلان می‌گیرند.
- **اپ روی دستگاه اجرا نشده** — فقط کامپایل و بررسی ایستا
