<div align="center">

# GetTube

### Video/Audio Downloader for Android — powered by yt-dlp

تطبيق أندرويد لتحميل الفيديو والصوت من يوتيوب وغيره — مبني على محرك **yt-dlp**
الذي يتحدث باستمرار ليتجاوز تغييرات يوتيوب وحجب الشبكات.

</div>

---

## ✨ المميزات / Features

- تحميل فيديو بأي جودة — من 144p حتى 8K
- استخراج الصوت فقط (MP3 / M4A / Opus) مع الغلاف والبيانات الوصفية
- تحميل قوائم التشغيل كاملة
- تحميل بالتوازي عبر aria2c + دمج سريع عبر FFmpeg
- استكمال التحميلات المتوقفة
- واجهة Material 3 بالكامل + دعم كامل للعربية و RTL
- مشاركة رابط من أي تطبيق → تحميل فوري (Share / Copy detection)
- **محرك yt-dlp قابل للتحديث من داخل التطبيق** (الإعدادات ← yt-dlp)

## 📥 التحميل / Download

> حمّل أحدث APK من صفحة [Releases](../../releases)

| الملف | مناسب لـ |
|-------|----------|
| `GetTube-arm64-*.apk` | الموبايلات الحديثة (الأغلبية — موصى به) |
| `GetTube-arm32-*.apk` | الأجهزة القديمة 32-bit |
| `GetTube-x86_64-*.apk` | المحاكيات وأجهزة Intel 64-bit |
| `GetTube-x86-*.apk` | أجهزة Intel 32-bit نادرة |

> كل ملف معمارية واحدة فقط (~40MB) — حمّل اللي يناسب جهازك بدل ملف واحد كبير 138MB.

## 🛠️ البناء من المصدر / Build from source

```bash
# يتطلب: JDK 21 + Android SDK
./gradlew assembleGenericRelease
# المخرجات: app/build/outputs/apk/generic/release/
```

أو عبر GitHub Actions: ادفع tag بالشكل `build-N` ليتم بناء ونشر APK تلقائياً.

## 🙏 الشكر والاعتماد / Credits

هذا المشروع مبني على مصدر [Seal](https://github.com/JunkFood02/Seal) بواسطة JunkFood02 —
وهو بدوره مبني على [YouGet](https://github.com/y20y20h/YouGet) بمكتبة
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android) ومحرك
[yt-dlp](https://github.com/yt-dlp/yt-dlp).

## 📄 الترخيص / License

[GPL-3.0](LICENSE) — راجع ملف [LICENSE](LICENSE) للتفاصيل الكاملة،
وحقوق التصميم الأصلي محفوظة لمطوري Seal و yt-dlp.
