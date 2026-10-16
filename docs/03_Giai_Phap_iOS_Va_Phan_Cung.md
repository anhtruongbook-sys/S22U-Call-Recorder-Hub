# Giải Pháp Ghi Âm Trên iPhone (iOS) & Thiết Bị Phần Cứng Độc Lập (Cho Mọi Ứng Dụng OTT)

Đối với người dùng **iPhone (iOS)** hoặc người dùng **Android không muốn Root máy** nhưng yêu cầu ghi âm ổn định 100% mọi cuộc gọi từ **SIM thường, Zalo, Viber, Telegram, WhatsApp, Messenger, Microsoft Teams**, giải pháp phần cứng thu âm qua cảm biến rung áp điện (Piezoelectric / Bone Conduction) hoặc phần mềm can thiệp trên iOS có Jailbreak/TrollStore là hai hướng tiếp cận chính.

---

## 1. Giải Pháp Phần Cứng Gắn Mặt Lưng (MagSafe Call Recorder - Không Phụ Thuộc Hệ Điều Hành)

Đây là giải pháp thực tế và ổn định nhất hiện nay cho cả iPhone và Android vì nó hoạt động ở **tầng vật lý (Acoustic & Vibration Layer)**, hoàn toàn miễn nhiễm với mọi bản cập nhật chặn ghi âm của iOS, Android hay Zalo/Telegram/WhatsApp.

### 1.1. Nguyên lý hoạt động: Cảm biến Rung Áp Điện (Piezoelectric VCS - Vibration Conduction Sensor)
1. Thiết bị có dạng một tấm thẻ siêu mỏng (dày khoảng 2.5mm – 3mm, tương đương thẻ ATM) hít chặt vào mặt lưng điện thoại thông qua vòng từ tính **MagSafe** (với Android dùng vòng dán từ tính kèm theo ốp lưng).
2. Khi bạn nghe điện thoại (áp điện thoại lên tai như bình thường, **không cần bật loa ngoài**):
   - **Loa thoại trong (Earpiece Speaker)** của điện thoại phát ra tiếng của người bên kia (dù gọi qua SIM thường, Zalo, Viber, Telegram hay WhatsApp).
   - Dao động âm thanh của loa thoại truyền trực tiếp qua **khung kim loại và mặt kính lưng** của điện thoại dưới dạng sóng rung cơ học.
   - **Cảm biến rung áp điện (VCS / Piezo Sensor)** áp sát mặt lưng điện thoại sẽ thu nhận trực tiếp sóng rung nội bộ này và chuyển đổi thành tín hiệu âm thanh rõ nét của người bên kia, đồng thời **Microphone MEMS** trên thiết bị thu giọng nói của bạn.

### 1.2. Các thiết bị phần cứng tiêu biểu
| Tên thiết bị | Cơ chế thu âm cuộc gọi | Tính năng nổi bật | Khả năng tương thích |
| :--- | :--- | :--- | :--- |
| **PLAUD NOTE / PLAUD NotePin** | Cảm biến rung dẫn truyền xương (VCS) + 2 Mic MEMS Knowles | Gạt nút vật lý 1 chạm là ghi âm; tích hợp ứng dụng đồng bộ qua Bluetooth/Wi-Fi, tự động gỡ băng (Transcribe) tiếng Việt cực chuẩn bằng Whisper/GPT-4o/Claude và phân tách người nói (Speaker Diarization). | Mọi dòng iPhone & Android; ghi được SIM thường, Zalo, Viber, Telegram, WhatsApp, FaceTime. |
| **Magmo / Magmo Pro** | Cảm biến Piezoelectric thu rung động khung máy | Lưu trữ trực tiếp vào bộ nhớ trong (32GB) như một ổ USB hoặc đồng bộ qua App; không tốn phí thuê bao AI nếu chỉ cần file ghi âm `.WAV`/`.MP3`. | Mọi dòng iPhone (MagSafe) & Android. |
| **Tai nghe ghi âm Bluetooth / Cáp chia DSP (ví dụ: Call Recorder Earphone)** | Chip DSP nằm giữa tai nghe và cổng Type-C / Lightning hoặc kết nối Bluetooth kép | Cho phép vừa đeo tai nghe đàm thoại vừa ghi âm 2 chiều (phù hợp khi không muốn áp điện thoại lên tai). | iPhone & Android qua cổng USB-C / Bluetooth. |

---

## 2. Giải Pháp Phần Mềm Trên iPhone (iOS)

### 2.1. Máy iPhone Nguyên Bản (Không Jailbreak / Không TrollStore)
- **Với cuộc gọi SIM thông thường & FaceTime (iOS 18.1 trở lên)**:
  - Bấm biểu tượng ghi âm ở góc trên bên trái màn hình cuộc gọi.
  - *Lưu ý*: Có âm báo giọng nói cho đầu dây bên kia biết cuộc gọi đang được ghi âm. Bản ghi được lưu tự động vào ứng dụng **Ghi chú (Notes)**.
- **Với Zalo, Viber, Telegram, WhatsApp trên iPhone nguyên bản**:
  - Do cơ chế Sandbox của iOS (`CallKit` khóa độc quyền `AVAudioSession`), **không có bất kỳ ứng dụng nào trên App Store** có thể ghi âm ngầm cuộc gọi Zalo/Viber/Telegram/WhatsApp trên cùng chiếc iPhone đó.
  - Các ứng dụng "Call Recorder" trên App Store thực chất chỉ là dịch vụ gọi ghép kênh 3 bên (3-way Conference Call) qua tổng đài viễn thông cho cuộc gọi SIM, hoàn toàn **không hoạt động với Zalo, Viber, Telegram, WhatsApp**.
  - **Giải pháp bắt buộc trên iPhone nguyên bản cho OTT**: Dùng thiết bị phần cứng MagSafe (như mục 1) hoặc gọi thông qua máy tính đồng bộ (xem mục 3).

### 2.2. Máy iPhone có TrollStore hoặc Jailbreak (iOS 14.0 – 17.0)
Nếu thiết bị iPhone nằm trong dải phiên bản hỗ trợ **TrollStore** (khai thác lỗ hổng CoreTrust để cài ứng dụng với quyền hệ thống `Entitlements` mà không cần Jailbreak) hoặc đã **Jailbreak (Dopamine / palera1n)**:
- **Ứng dụng**: **AudioRecorder XS (by Limneos)** hoặc **TrollRecorder**.
- **Cơ chế hoạt động**:
  - Can thiệp trực tiếp vào daemon âm thanh hệ thống của iOS (`mediaserverd` / `audiomxd`).
  - Thu âm trực tiếp 2 chiều (Digital In-line Recording) cho toàn bộ: **Cuộc gọi SIM, FaceTime, Zalo, Viber, Telegram, WhatsApp, Messenger, Line** mà không cần bật loa ngoài và không phát bất kỳ tiếng thông báo nào.

---

## 3. Giải Pháp Đồng Bộ Qua Máy Tính (Desktop Companion - Zalo PC / Telegram / Viber / WhatsApp Desktop)

Khi làm việc cố định hoặc có thể nhận/gọi qua phiên bản máy tính (Zalo PC, Viber Desktop, Telegram Desktop, WhatsApp Desktop), việc ghi âm trở nên đơn giản và đạt chất lượng phòng thu (Lossless Dual-Channel):
- Trên Windows, kiến trúc âm thanh **WASAPI Loopback** cho phép một chương trình Python hoặc phần mềm ghi âm thu lại toàn bộ âm thanh phát ra từ loa/tai nghe (chiều người bên kia nói trên Zalo/Telegram/Viber/WhatsApp) **đồng thời** với âm thanh từ Microphone (chiều bạn nói) mà không hề bị chặn như trên điện thoại.
- Tham khảo mã nguồn công cụ mẫu tại thư mục: `prototypes/pc_companion_recorder/`.
