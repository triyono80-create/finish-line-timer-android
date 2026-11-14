# FINISH LINE TIMER – Android Native

Project Android native awal untuk OPPO A77s.

## Fitur prototipe
- Kamera belakang menggunakan CameraX.
- Rekam video ke `Movies/FinishLineTimer`.
- OCR on-device menggunakan bundled ML Kit Text Recognition.
- Pola nomor awal: satu huruf A-Z diikuti 2–5 digit.
- Membutuhkan dua pembacaan konsisten sebelum menyimpan nomor.
- Mencegah nomor yang sama tercatat dua kali dalam satu instalasi.
- Menyimpan catatan lokal dan mengucapkan nomor melalui Text-to-Speech.
- Tombol RESET DATA LOMBA dengan dialog konfirmasi; menghapus catatan finisher dan nomor terakhir tanpa menghapus video yang sudah direkam.

## Build APK melalui GitHub Actions
1. Unggah seluruh isi folder proyek ini ke root repository GitHub.
2. Buka tab Actions.
3. Pilih `Build Finish Line Timer APK`.
4. Tekan `Run workflow` jika belum berjalan otomatis.
5. Setelah workflow selesai, buka hasil run dan unduh artifact `finish-line-timer-debug-apk`.
6. Ekstrak ZIP artifact untuk memperoleh `app-debug.apk`, lalu instal di Android.

## Catatan penting
- Ini prototipe awal, belum divalidasi untuk penentuan hasil resmi.
- OCR dapat salah membaca nomor, terutama saat pelari bergerak cepat, nomor kecil, miring, tertutup, atau blur.
- Implementasi saat ini mencatat waktu saat OCR mengonfirmasi nomor, bukan waktu presisi saat badan melintasi garis finis. Untuk timing resmi diperlukan kalibrasi garis, buffer frame/video, dan pengujian lapangan.
- Data peserta dan hasil saat ini disimpan lokal di SharedPreferences; ekspor CSV, pengelolaan lomba, dan pencadangan belum dibuat.
- ML Kit bundled model dimasukkan ke APK agar pengenalan teks dapat berjalan offline setelah APK terpasang. Build awal tetap memerlukan internet untuk mengambil dependensi.
- Uji kamera, konsumsi baterai, suhu, kapasitas video, dan akurasi OCR pada perangkat sasaran sebelum perlombaan.
