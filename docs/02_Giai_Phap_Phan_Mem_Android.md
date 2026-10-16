# Giải Pháp Phần Mềm Ghi Âm Cuộc Gọi Trên Android (Gọi Thường & OTT)

Tài liệu này phân loại và hướng dẫn chi tiết các giải pháp phần mềm trên Android theo 3 cấp độ: **Cấp độ 1 (Không Root - Chính thống cho Gọi thường)**, **Cấp độ 2 (Không Root - Dùng Trợ năng cho OTT)**, và **Cấp độ 3 (Can thiệp Hệ thống / Root - Thu âm 2 chiều kỹ thuật số cho cả Gọi thường & Zalo/Viber/Telegram/WhatsApp)**.

---

## 1. Cấp Độ 1: Ghi Âm Cuộc Gọi Thông Thường (Cellular) Không Cần Root, Không Tiếng Thông Báo

Trên điện thoại Android, khả năng ghi âm cuộc gọi SIM thông thường phụ thuộc vào **Trình gọi điện mặc định (Default Dialer)** và **Mã vùng phần mềm (CSC / Region ROM)**:

### 1.1. Điện thoại Samsung Galaxy
- **Đặc điểm**: Máy chính hãng phân phối tại Việt Nam (mã CSC: `XXV`), Thái Lan (`THL`), Ấn Độ (`INS`) sử dụng Samsung Dialer có tích hợp sẵn tính năng **"Tự động ghi âm cuộc gọi" (Auto Call Recording)** ở cấp độ phần cứng (`VOICE_CALL`), chất lượng rõ nét tuyệt đối cả 2 chiều và **không phát âm báo** cho người bên kia.
- **Xử lý khi máy xách tay (Hàn Quốc, Mỹ, Châu Âu) không có ghi âm**:
  - Có thể đổi mã CSC sang `XXV` (Việt Nam) hoặc `THL` (Thái Lan) thông qua công cụ **SamFW Tool** trên máy tính (qua cổng USB ở chế độ MTP/ADB) mà **không làm mất dữ liệu, không nhảy Knox (0x0), không cần Root**.
  - Cách bật sau khi có CSC Việt Nam: Mở ứng dụng **Điện thoại** $\rightarrow$ Dấu 3 chấm $\rightarrow$ **Cài đặt** $\rightarrow$ **Ghi âm cuộc gọi** $\rightarrow$ Bật **Tự động ghi âm cuộc gọi**.

### 1.2. Điện thoại Xiaomi / Redmi / POCO (MIUI & HyperOS)
- **Đặc điểm**: Các máy bán chính hãng quốc tế (Global ROM) thường bị ép dùng **Google Dialer** (khi bấm ghi âm sẽ phát tiếng *"Cuộc gọi này đang được ghi âm"* cho cả 2 bên nghe thấy và không có chế độ tự động ghi mọi cuộc gọi).
- **Giải pháp**:
  - Cài đặt **ROM Quốc tế Indonesia (ID)**, **ROM Đài Loan (TW)**, hoặc **ROM Xiaomi.eu**: Các bản ROM này sử dụng **MIUI/HyperOS Dialer gốc**, hỗ trợ ghi âm tự động 2 chiều im lặng hoàn toàn.
  - Ngoài ra, một số dòng máy cho phép thay thế bằng ứng dụng **ODialer** (trình gọi điện chính thức của OPPO/OnePlus trên Google Play) để giảm thiểu tiếng thông báo dài bằng một tiếng "bíp" cực ngắn.

---

## 2. Cấp Độ 2: Ghi Âm Zalo, Viber, Telegram, WhatsApp Trên Máy KHÔNG ROOT (Non-Root)

Đối với thiết bị Android không Root, phương pháp khả thi nhất bằng phần mềm là sử dụng các ứng dụng khai thác quyền `AccessibilityService` kết hợp nguồn âm thanh `VOICE_RECOGNITION`.

### 2.1. Các ứng dụng tiêu biểu
1. **Cube ACR (Cube Call Recorder)** + **Cube ACR Helper**
2. **ACR Phone (by NLL Apps)** + **APH (ACR Phone Helper)**

### 2.2. Cấu hình kỹ thuật tối ưu để thu được tiếng người bên kia trên Zalo/Telegram/Viber/WhatsApp
Vì hệ điều hành chặn thu trực tiếp luồng `USAGE_VOICE_COMMUNICATION` trên máy không Root, cần cấu hình theo các bước sau để tối ưu hóa độ rõ của chiều người bên kia nói:
1. **Cấp quyền Trợ năng (Accessibility Service)**: Bắt buộc bật dịch vụ App Connector trong phần *Cài đặt $\rightarrow$ Hỗ trợ tiếp cận (Accessibility)*.
2. **Tắt Tối ưu hóa Pin (Unrestricted Battery)**: Cho phép ứng dụng chạy nền không hạn chế để hệ thống không đóng tiến trình khi đang gọi Zalo/Viber.
3. **Chọn nguồn âm thanh VoIP (VoIP Audio Source)**:
   - Trong cài đặt của Cube ACR $\rightarrow$ *Recording $\rightarrow$ VoIP Recording*: Thử nghiệm chuyển đổi giữa **`voice_recognition`** (Mặc định tốt nhất trên đa số chip Snapdragon) và **`voice_communication`**.
4. **Quy tắc Vật lý Bắt buộc khi Không Root**:
   - **KHÔNG đeo tai nghe (Bluetooth hoặc có dây)** khi cần ghi âm cuộc gọi Zalo/Viber/Telegram/WhatsApp trên máy không Root, vì khi đeo tai nghe, âm thanh đầu bên kia không phát ra loa ngoài/loa thoại nên Mic không thể thu lại được.
   - Bật **Loa ngoài (Speakerphone)** hoặc tăng âm lượng loa thoại lên mức 80–100% trong phòng yên tĩnh để cảm biến Mic thu được âm thanh phản xạ qua khung máy.

---

## 3. Cấp Độ 3: Giải Pháp Triệt Để 100% Bằng Root / System Hook (Thu Âm Kỹ Thuật Số 2 Chiều Kể Cả Khi Đeo Tai Nghe)

Nếu bạn có một thiết bị Android chuyên dụng làm máy công việc đã **Unlock Bootloader** và cài đặt **Magisk / KernelSU + Zygisk + LSPosed**, bạn có thể vượt qua hoàn toàn rào cản của hệ điều hành để ghi âm trực tiếp luồng dữ liệu số (Digital PCM Stream) của cả cuộc gọi thường lẫn Zalo, Viber, Telegram, WhatsApp.

### 3.1. Đối với Cuộc gọi thường (Cellular): Module BCR (Basic Call Recorder)
- **Tác giả**: `chenxiaolong` (Mã nguồn mở trên GitHub).
- **Cơ chế hoạt động**: Cài đặt dưới dạng một Magisk/KernelSU Module, tự động gắn ứng dụng vào phân vùng hệ thống `/system/priv-app/` với đầy đủ quyền đặc quyền `CAPTURE_AUDIO_OUTPUT`.
- **Ưu điểm**: Ghi âm tự động 100% cuộc gọi SIM ở định dạng FLAC/OGG/WAV/OPUS, tách riêng 2 kênh trái/phải (Kênh trái: Người gọi, Kênh phải: Người nhận) phục vụ chạy AI Speech-to-Text cực kỳ chính xác.

### 3.2. Đối với Zalo, Viber, Telegram, WhatsApp: Xposed / LSPosed Hook
Để ghi âm trực tiếp luồng âm thanh kỹ thuật số của các ứng dụng OTT (ngay cả khi đang đeo tai nghe Bluetooth hoặc ở nơi ồn ào), sử dụng kỹ thuật Hook vào tầng Framework:

#### Phương án A: Mở khóa Internal Audio Capture (`AudioPlaybackCapture` Bypass)
Sử dụng module LSPosed (như **Enable Screenshot / Audio Capture Bypass** hoặc viết module Xposed hook vào `android.media.AudioAttributes` và `com.android.server.audio.AudioService`):
1. Hook vào phương thức khởi tạo `AudioTrack` của các package:
   - Zalo: `com.zing.zalo`
   - Viber: `com.viber.voip`
   - Telegram: `org.telegram.messenger`
   - WhatsApp: `com.whatsapp`
2. Ép cờ `ALLOW_CAPTURE_BY_ALL` cho toàn bộ các luồng `AudioTrack` và cho phép `AudioPlaybackCapture` thu nhận cả luồng `USAGE_VOICE_COMMUNICATION`.
3. Khi đó, trình quay màn hình nội bộ hoặc ứng dụng ghi âm qua `MediaProjection` sẽ thu được **100% âm thanh gốc từ máy chủ gửi về (Downlink)** kết hợp với **Microphone (Uplink)** mà không bị suy hao hay phụ thuộc vào loa ngoài.

#### Phương án B: Hook trực tiếp vào Native `libaudioclient.so` / `AudioFlinger`
Các công cụ chuyên sâu (như **Call Recorder - SKVALEX** kèm Magisk Add-on hoặc module Zygisk tùy chỉnh) can thiệp trực tiếp vào tiến trình `audioserver` của Android:
- Bắt các buffer PCM trong hàm `AudioTrack::write()` (âm thanh người bên kia nói trên Zalo/Viber/Telegram/WhatsApp) và `AudioRecord::read()` (âm thanh từ Mic của bạn).
- Trộn (Mux) hai luồng PCM này thành một tệp âm thanh Stereo hoàn chỉnh.
