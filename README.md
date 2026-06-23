# AMY Elite Suite - Native Wrapper

Aplikasi Android Native (Kotlin) yang berfungsi sebagai pembungkus dan mesin tempur di latar belakang untuk PWA **AMY FX Autopilot Radar**.

## 🚀 Fungsi Asli & Arsitektur

Aplikasi ini bukan sekadar *WebView* biasa. Ini adalah ekosistem hibrida yang dirancang untuk mengatasi kelemahan Doze Mode Android agar aplikasi *trading* tetap bisa memantau pasar saat layar dimatikan.

### 1. Native Background Scanner (`ScannerService.kt`)
Sebuah `Foreground Service` cerdas yang mendelegasikan beban komputasi.
- **Tanpa Hidden WebView**: Tidak menggunakan trik WebView tersembunyi yang boros memori.
- **Native WebSocket (`OkHttp`)**: Membuka koneksi `wss://ws.twelvedata.com` murni dari Kotlin.
- **Smart Delegation**: PWA mendikte target `BSL` (Buy-Side) dan `SSL` (Sell-Side) kepada Android. Android hanya bertugas memelototi harga, dan langsung menembakkan Notifikasi jika target tertembus.

### 2. PWA Bridge (`WebAppInterface`)
Menjembatani kode React/Javascript dengan Native Android:
- `@JavascriptInterface startBackgroundScanner(apiKey, bsl, ssl)`: Memicu *Service* di background.
- Mengamankan *API Key* ke dalam sistem `SharedPreferences` Android lokal.
- Menampilkan Heads-Up Notification (Push) berlogo khusus `ic_stat_amy_fx`.

### 3. Ultimate WebView
- Menjalankan antarmuka *React* secara mulus tanpa *caching* agresif yang merusak real-time data.
- Menangani unduhan otomatis (seperti *Event Logs .txt*) dengan `DownloadListener`.

## ⚙️ Syarat Kompilasi (Build)
- **Min SDK**: 24
- **Target SDK**: 34 (Siap untuk standar keamanan Foreground Service Android 14).
- Membutuhkan izin *Wakelock* (`PARTIAL_WAKE_LOCK`) dan *Notifications*.

Aplikasi ini menolak dikalahkan oleh OS Android demi memastikan setiap momentum *trading* emas tak pernah terlewatkan!
