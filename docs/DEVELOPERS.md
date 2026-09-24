# khatwa: developer notes

<div dir="rtl">

هذه الصفحة لمن يبني التطبيق بنفسه أو يعدّل الكود. المستخدم العادي لا يحتاجها؛ صفحة [README](../README.md) تكفيه.

## المستودع

- `core/` وحدة Kotlin/JVM نقية: عدّ الخطوات مع الإقلاع ومنتصف الليل، الجلسات، الهدف التدرّجي، السلاسل، أكواد اللابتوب، ترتيب المجموعات. اختباراتها تعمل بلا Android SDK.
- `app/` تطبيق أندرويد: Kotlin + Jetpack Compose + Room + WorkManager + Vico + Firebase.
- `laptop-lock/` برنامج ويندوز: C#، .NET 8، WinForms.
- `shared/hmac-vectors.json` قيم ثابتة لأكواد اللابتوب يتحقق منها اختبار Kotlin واختبار C# معاً.
- `firebase/` قواعد أمان Firestore واختباراتها على المحاكي.
- `docs/screenshots/` لقطات README، مأخوذة من محاكي CI.
- النصوص كلها في `app/src/main/java/com/khatwa/app/i18n/` (`Ar.kt` و`En.kt`). لإضافة لغة أنشئ كائناً ثالثاً يطبّق `Strings`.

## أكواد اللابتوب

- كود اقتران من 8 أرقام، ثم لكل قفل للجوال عدّاد (1، 2، 3...) يُشتق منه كودان من 6 أرقام بطريقة تطبيقات التحقق بخطوتين: HMAC-SHA256 ثم اقتطاع RFC 4226.
- اللابتوب يبحث عن كود القفل في الـ 1000 عدّاد التالية لآخر عدّاد قبله. الجوال يتخطى أي عدّاد يتكرر كود قفله مع عدّاد قريب قبله، فيطابق كود الفتح دائماً.
- التفاصيل الكاملة في `core/.../laptop/LaptopCode.kt` و`laptop-lock/KhatwaLock.Core/ChallengeCodes.cs`.

## البناء والاختبار

- محلياً: `./gradlew :core:test :app:assembleDebug` (يحتاج Android SDK وJDK 17)، و`dotnet test laptop-lock/KhatwaLock.Tests` لويندوز، و`cd firebase && npm install && npm test` لقواعد Firestore (يحتاج Node 22 وJava 21).
- **CI** (`.github/workflows/ci.yml`) عند كل push على `main` وفروع `claude/**`:
  - Android: اختبارات الوحدة، APK debug وrelease، فحص خلوّ نسخة release من أدوات الاختبار، محاكيات Firebase، ثم محاكي أندرويد (API 34) يشغّل كل الاختبارات ويرفع الأدلة باسم `emulator-evidence`.
  - Firestore: اختبارات قواعد الأمان.
  - Windows: اختبارات C# وبناء exe واحد مستقل.
- **مصدر خطوات وهمي**: نسخة debug فقط تحتوي `FakeStepSource` ومستقبِل adb (`app/src/debug`). نسخة release لا تحتوي أياً منهما ويُفحص ذلك في CI (`ci/verify-release.sh`).

## النشر

- `.github/workflows/release.yml`: دفع وسم مثل `v0.3.0`، أو تشغيل الـ workflow يدوياً من تبويب Actions مع رقم الإصدار. يبني APK موقّعاً وexe ويرفقهما بصفحة Release.
- **الأسرار** (Settings ← Secrets and variables ← Actions):
  - التوقيع: `KHATWA_KEYSTORE_BASE64` و`KHATWA_KEYSTORE_PASSWORD` و`KHATWA_KEY_ALIAS` و`KHATWA_KEY_PASSWORD`. بدونها يُوقَّع بمفتاح debug.
  - Firebase: `KHATWA_GOOGLE_SERVICES_BASE64`. بدونه يُستخدم `app/google-services.placeholder.json` وتظهر المجموعات «غير متاحة».
  - لا keystore ولا `google-services.json` في المستودع.

## إعداد Firebase لمشروعك

1. أنشئ مشروعاً في `console.firebase.google.com` (الخطة المجانية Spark تكفي).
2. **Firestore Database**: أنشئ قاعدة بيانات (وضع الإنتاج)، ثم تبويب **Rules**، الصق محتوى `firebase/firestore.rules` كاملاً، ثم **Publish**.
3. **Authentication** ← Sign-in method ← فعّل **Anonymous**.
4. أضف تطبيق أندرويد باسم الحزمة `com.khatwa.app` ونزّل `google-services.json`. ضعه في `app/google-services.json` محلياً، أو كسرّ GitHub باسم `KHATWA_GOOGLE_SERVICES_BASE64` (الملف مرمّزاً base64).
5. اختياري: **App Check** بمزوّد Play Integrity (يحتاج بصمة SHA-256 لمفتاح التوقيع، تجدها في مخرجات CI «apksigner verify»). لا تفعّل «Enforce» قبل التأكد أن النسخة الموقّعة تعمل.

## حدود معروفة للمجموعات

بلا خادم وسيط (خطة Spark)، مجموع كل مجموعة يُحسب من أرقام يرسلها كل جهاز بنفسه. مستخدم تقني يستطيع عبر واجهة Firebase مباشرة رؤية أرقام مجهولة الهوية بلا أسماء، والتلاعب بأرقامه هو فقط؛ القواعد ترفض ما يتجاوز 60 ألف خطوة في اليوم، و5 مجموعات لكل مالك، و200 عضو لكل مجموعة.

الرخصة: MIT.

</div>
