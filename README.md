# File Manager + (Android Native App)

Ứng dụng quản lý tệp tin Android hiện đại được xây dựng 100% bằng **Kotlin** và **Jetpack Compose**, mô phỏng chính xác giao diện và các tính năng cốt lõi của **File Manager +** (đã tinh chỉnh theo yêu cầu).

---

## 📱 Ánh Xạ Tính Năng & Màn Hình

| Màn hình | Tính năng chính |
|---|---|
| **1. Home Dashboard** | • Thẻ dung lượng **Main Storage** (Dung lượng đã dùng / Tổng dung lượng)<br>• Lưới 9 danh mục: **Main storage, Downloads, Images, Audio, Videos, Documents, Cloud, Access from network, Recycle Bin** |
| **2. Access from network** | • Tích hợp **Embedded FTP Server** cục bộ chạy dưới dạng Foreground Service<br>• Tùy chọn: Port (1524), Mật khẩu ngẫu nhiên/tự đặt, Show hidden files<br>• Nút **START SERVICE / STOP SERVICE** hiển thị địa chỉ `ftp://IP:Port` để truy cập từ PC qua Wi-Fi |
| **3. Cloud Storage** | • Quản lý các tài khoản đám mây: **Google Drive, Dropbox, OneDrive, WebDAV**<br>• Thêm mới tài khoản (`+ Add a cloud location`)<br>• Duyệt tệp, tải lên/tải xuống |
| **4. Images Explorer** | • Breadcrumb Navigation (`Home > Images`)<br>• Lưới 3 cột hiển thị các album ảnh (Screenshots, Messenger, Zalo, Pictures, Download...)<br>• Thumbnail ảnh mới nhất kèm **Huy hiệu (Badge) ứng dụng nguồn** ở góc ảnh<br>• Trình xem ảnh nội bộ phóng to/thu nhỏ |
| **5. Audio Explorer** | • Breadcrumb Navigation (`Home > Audio`)<br>• Danh sách Album/Folder nhạc (Download, super_sound, Dabin, VN...) kèm cover art và badge nốt nhạc<br>• Số lượng bài hát trong từng thư mục |
| **6. Videos Explorer** | • Breadcrumb Navigation (`Home > Videos`)<br>• Lưới 3 cột hiển thị video album với badge cuộn phim / ứng dụng<br>• Tích hợp trình phát video **ExoPlayer / Jetpack Media3** |
| **7. File Browser** | • Duyệt cây thư mục bộ nhớ trong (`/storage/emulated/0`)<br>• Thao tác tập tin: Copy, Cut, Paste, Rename, Create Folder, Zip/Unzip<br>• Chế độ chọn nhiều tệp (Multi-selection) |
| **8. Recycle Bin** | • Cơ chế Soft-delete đưa file vào thư mục `.filemanager_trash/`<br>• Khôi phục (Restore) về vị trí cũ hoặc Xóa vĩnh viễn (Permanent Delete) |

---

## 🛠️ Công Nghệ & Kiến Trúc

- **Ngôn ngữ**: Kotlin (Coroutines Flow)
- **Giao diện**: Jetpack Compose + Material Design 3 (Chuẩn Dark AMOLED Theme `#121212` / `#000000`)
- **Kiến trúc**: Clean Architecture + MVI / MVVM
- **Dependency Injection**: Dagger Hilt
- **Cơ sở dữ liệu**: Room Database (quản lý Thùng rác & Cloud)
- **Tải ảnh & Thumbnail**: Coil với VideoFrameDecoder
- **Trình phát Media**: Jetpack Media3 (ExoPlayer)
- **Máy chủ FTP**: Apache MINA FtpServer
- **Nén/Giải nén**: Zip4j

---

## 🚀 Hướng Dẫn Mở Dự Án Trong Android Studio

1. Mở **Android Studio** (Hedgehog / Iguana / Jellyfish hoặc mới hơn).
2. Chọn **File > Open** và dẫn đến thư mục `File Manager`.
3. Chờ Gradle đồng bộ (Sync Project with Gradle Files).
4. Kết nối thiết bị thật (hoặc máy ảo Android 11+) và nhấn **Run (Shift + F10)**.
