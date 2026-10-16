# Phân Tích Kiến Trúc Âm Thanh Hệ Điều Hành & Cơ Chế Chặn Ghi Âm Cuộc Gọi

Tài liệu này phân tích chi tiết cơ chế xử lý luồng âm thanh (Audio Subsystem) trên Android (từ Android 9 đến Android 15+) và iOS (iOS 17–18+), lý giải nguyên nhân kỹ thuật vì sao việc ghi âm cuộc gọi thông thường và cuộc gọi OTT (Zalo, Viber, Telegram, WhatsApp) bị giới hạn ở tầng hệ điều hành.

---

## 1. Kiến Trúc Âm Thanh Trên Android (AudioFlinger & HAL)

### 1.1. Các nguồn thu âm chuẩn (`MediaRecorder.AudioSource`)
Trong Android SDK, các ứng dụng muốn thu âm phải khởi tạo `AudioRecord` hoặc `MediaRecorder` với một trong các hằng số nguồn âm thanh sau:

| Hằng số `AudioSource` | Mô tả kỹ thuật | Quyền yêu cầu | Trạng thái trên Android 10+ |
| :--- | :--- | :--- | :--- |
| `VOICE_CALL` (4) | Thu trực tiếp cả chiều lên (Uplink - Mic) và chiều xuống (Downlink - Người bên kia) từ Modem/Baseband. | `CAPTURE_AUDIO_OUTPUT` (Quyền `signature|privileged`) | **Chặn hoàn toàn** đối với ứng dụng bên thứ 3 (chỉ ứng dụng hệ thống trong `/system/priv-app` mới gọi được). |
| `VOICE_COMMUNICATION` (7) | Tối ưu cho cuộc gọi VoIP (Zalo, Viber, Telegram, WhatsApp) với khử vọng âm học (AEC - Acoustic Echo Cancellation) và giảm nhiễu (NS). | `RECORD_AUDIO` | Ứng dụng OTT chiếm quyền nguồn này khi gọi. |
| `VOICE_RECOGNITION` (6) | Nguồn thu âm phục vụ nhận diện giọng nói, ít bị can thiệp bởi bộ lọc khử vọng (AEC) hơn so với `MIC`. | `RECORD_AUDIO` | Các ứng dụng ghi âm Non-Root (như Cube ACR) lợi dụng nguồn này kèm `AccessibilityService` để thu âm. |
| `REMOTE_SUBMIX` (8) | Thu toàn bộ âm thanh phát ra của hệ thống (Internal Audio). | `CAPTURE_AUDIO_OUTPUT` hoặc qua `MediaProjection` API | Bị chặn nếu ứng dụng đích đặt `android:allowAudioPlaybackCapture="false"` hoặc sử dụng luồng `USAGE_VOICE_COMMUNICATION`. |

### 1.2. Cơ chế "Sharing Audio Input" trên Android 10+ (API 29+)
Từ Android 10, Google áp dụng quy tắc ưu tiên độc quyền Microphone (Concurrent Audio Capture Policy):
1. Khi **Zalo, Viber, Telegram hoặc WhatsApp** thực hiện cuộc gọi, ứng dụng OTT mở luồng thu âm với cờ `AudioSource.VOICE_COMMUNICATION`.
2. Theo chính sách của `AudioPolicyService` trong nhân Android (`frameworks/av/services/audiopolicy/`):
   - Một ứng dụng thông thường chạy nền yêu cầu `RECORD_AUDIO` sẽ **không nhận được dữ liệu âm thanh** (chỉ nhận các byte `0x00` - im lặng) khi một ứng dụng khác đang giữ `VOICE_COMMUNICATION`.
   - **Ngoại lệ duy nhất cho ứng dụng không Root**: Một dịch vụ trợ năng (`AccessibilityService`) đang hoạt động trên màn hình và sử dụng `AudioSource.VOICE_RECOGNITION` được phép đọc đồng thời luồng Microphone cùng với ứng dụng OTT.
   - **Hạn chế của ngoại lệ này**: `AccessibilityService` chỉ đọc được **chiều Microphone của chính bạn (Uplink)**. Đối với **chiều người bên kia nói (Downlink)**:
     - Nếu người dùng đeo tai nghe (Bluetooth / Có dây): Âm thanh người bên kia đi thẳng ra tai nghe, Microphone không thu được $\rightarrow$ **File ghi âm chỉ có tiếng của bạn, mất hoàn toàn tiếng người bên kia**.
     - Nếu bật loa ngoài (Speakerphone) hoặc loa thoại đủ lớn: Tiếng người bên kia lọt qua vỏ máy/không khí vào Microphone, nhưng bộ lọc **AEC (Acoustic Echo Cancellation)** phần cứng của chip Qualcomm/MediaTek sẽ cố gắng xóa bỏ tiếng vọng đó.

### 1.3. Vì sao tính năng Quay màn hình (Screen Recorder) không thu được tiếng Zalo / Telegram / WhatsApp?
Nhiều người dùng thử bật trình "Quay màn hình" có sẵn trên điện thoại với tùy chọn "Thu âm thanh hệ thống + Mic", nhưng kết quả video xuất ra bị mất tiếng khi bắt đầu cuộc gọi OTT. Nguyên nhân:
- Luồng âm thanh phát ra của cuộc gọi OTT được gắn thuộc tính `AudioAttributes.USAGE_VOICE_COMMUNICATION`.
- Trong mã nguồn Android (`AudioPlaybackCapture`), Google chỉ cho phép chụp lại các luồng có thuộc tính `USAGE_MEDIA`, `USAGE_GAME`, hoặc `USAGE_UNKNOWN`.
- Mọi luồng `USAGE_VOICE_COMMUNICATION` đều bị loại trừ cứng ở tầng `AudioFlinger` để tuân thủ luật viễn thông quốc tế.

---

## 2. Kiến Trúc Âm Thanh Trên iOS (iPhone)

### 2.1. Cơ chế Sandbox và AVAudioSession trên iOS
Trên iOS, mọi ứng dụng chạy trong môi trường Sandbox tách biệt:
- Khi có cuộc gọi GSM/FaceTime hoặc cuộc gọi VoIP tích hợp `CallKit` (Zalo, Viber, Telegram, WhatsApp trên iOS đều tích hợp `CallKit`), hệ thống kích hoạt phiên âm thanh ưu tiên cao (`AVAudioSessionCategoryPlayAndRecord` với mode `AVAudioSessionModeVoiceChat`).
- Ngay khi `CallKit` được kích hoạt, hệ điều hành iOS gửi thông báo `AVAudioSessionInterruptionNotification` (`AVAudioSessionInterruptionTypeBegan`) tới tất cả các ứng dụng khác đang mở (kể cả ứng dụng Ghi âm - Voice Memos của Apple) và **ngắt cưỡng bức** quyền truy cập phần cứng âm thanh của chúng.
- Không tồn tại quyền kiểu `AccessibilityService` cho phép đọc chung Microphone trên iOS như trên Android.

### 2.2. Tính năng Ghi âm Cuộc gọi trên iOS 18.1+
Từ iOS 18.1, Apple bổ sung nút ghi âm trực tiếp trên giao diện gọi điện mặc định của iPhone, tuy nhiên có các giới hạn sau:
1. **Chỉ áp dụng cho cuộc gọi thông thường (Cellular) và FaceTime Audio**: Không hoạt động trên giao diện cuộc gọi của Zalo, Viber, Telegram, WhatsApp.
2. **Phát thông báo bắt buộc**: Ngay khi bấm nút ghi âm, hệ thống phát đoạn âm thanh giọng nói tự động *"Cuộc gọi này sẽ được ghi âm" (This call will be recorded)* cho cả hai bên cùng nghe thấy trước khi bắt đầu thu.
