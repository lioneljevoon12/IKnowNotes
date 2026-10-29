# Catatan Setup — Android UI IKnowNotes

## Cara Menjalankan

1. **Jalankan backend Django**:
   ```bash
   cd backend
   .\venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
   ```
2. **Jalankan aplikasi Android**:
   - Buka folder `android/` di Android Studio.
   - Bila test di **emulator**: `BASE_URL` sudah diset ke `http://10.0.2.2:8000/`.
   - Bila test di **HP fisik**: ganti `BASE_URL` di `data/remote/RetrofitClient.kt` ke IP LAN komputer (contoh `http://192.168.1.5:8000/`).
   - Run aplikasi ke emulator atau device.

## Status MVP & Perbaikan yang Sudah Dilakukan

### 1. Backend (Django REST Framework)
- **Bug Unarchive Teratasi**: Logika query `NoteViewSet.get_queryset()` diperbaiki agar action `unarchive` dan `retrieve` dapat mengakses catatan terarsip milik user tanpa error 404.
- **Keamanan Folder (IDOR Prevention)**: `NoteSerializer` sekarang memvalidasi dan membatasi folder hanya milik user yang sedang aktif.
- **ALLOWED_HOSTS**: Diperbarui ke `['*']` di `settings.py` agar emulator dan device HP fisik dapat terhubung tanpa blokir host.
- **Dependency Manifest**: Dibuat file `backend/requirements.txt`.
- **Automated Test**: Unit test lengkap di `notes/tests.py` (Registrasi, JWT, CRUD Folder, CRUD Note, Archive & Unarchive) berjalan sukses (**4 tests OK**).

### 2. Frontend (Android Kotlin)
- **Layar Catatan Terarsip**: Dibuat `ArchivedNotesActivity` lengkap dengan list masonry grid, status kosong, pull-to-refresh, dan tombol unarchive/delete di layar detail.
- **Manajemen Folder Lengkap**:
  - Tekan chip `+ Folder` untuk menambah folder baru.
  - Tahan (long-click) chip folder untuk opsi **Ubah Nama (Rename)** atau **Hapus Folder (Delete)**.
- **Perbaikan Tema Mode Malam (Dark Mode)**: Tema `Theme.IKnowNotes` di `res/values-night/themes.xml` diselaraskan agar tidak bentrok dengan manifest.
- **Verifikasi Build**: Proyek sudah diverifikasi dan sukses di-compile (`gradlew compileDebugKotlin` **BUILD SUCCESSFUL**).
