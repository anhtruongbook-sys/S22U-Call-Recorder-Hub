# Nghiên Cứu Giải Pháp Ghi Âm Cuộc Gọi Đa Nền Tảng (Cellular & OTT: Zalo, Viber, Telegram, WhatsApp)

Thư mục này lưu trữ hồ sơ nghiên cứu kỹ thuật, phân tích kiến trúc âm thanh hệ điều hành (Android/iOS) và các phương án triển khai ghi âm cuộc gọi 2 chiều cho:
1. **Cuộc gọi viễn thông thông thường (Cellular / GSM / VoLTE)**
2. **Cuộc gọi qua ứng dụng OTT (VoIP): Zalo, Viber, Telegram, WhatsApp**

---

## 1. Cấu Trúc Thư Mục Nghiên Cứu

```text
Nghien_Cuu_Ghi_Am_Cuoc_Goi/
├── README.md                                  # Tổng quan kiến trúc & Ma trận đánh giá giải pháp
├── docs/
│   ├── 01_Kien_Truc_Audio_OS_Rao_Can.md       # Phân tích cơ chế khóa ghi âm trên Android (10-15) & iOS
│   ├── 02_Giai_Phap_Phan_Mem_Android.md       # Giải pháp phần mềm Android (Non-Root, Accessibility, System/Root Hook)
│   ├── 03_Giai_Phap_iOS_Va_Phan_Cung.md       # Giải pháp cho iPhone & Phần cứng ghi âm (Piezo MagSafe, Bluetooth DSP)
│   └── 04_Khia_Canh_Phap_Ly_Bao_Mat.md        # Quy định pháp luật Việt Nam về ghi âm & bảo vệ dữ liệu cá nhân
├── prototypes/
│   └── pc_companion_recorder/                 # Công cụ ghi âm tự động 2 chiều qua Desktop Companion (Zalo/Telegram/Viber/WhatsApp PC)
└── scripts/                                   # Script kiểm tra quyền Audio & ADB Diagnostics cho Android
```

---

## 2. Bản Chất Kỹ Thuật: Tại Sao Ghi Âm OTT (Zalo, Viber, Telegram, WhatsApp) Khó Hơn Gọi Thường?

| Tiêu chí kỹ thuật | Gọi thông thường (Cellular / VoLTE) | Gọi OTT (Zalo, Viber, Telegram, WhatsApp) |
| :--- | :--- | :--- |
| **Luồng âm thanh (Audio Path)** | Đi qua Baseband Processor (Modem) và tuyến `VOICE_CALL` chuyên biệt của phần cứng. | Đi qua tầng ứng dụng (Application Layer) sử dụng giao thức WebRTC / VoIP và luồng `VOICE_COMMUNICATION`. |
| **Cơ chế chiếm quyền Mic** | Trình gọi điện mặc định của hãng (OEM Dialer) có quyền hệ thống `CAPTURE_AUDIO_OUTPUT`. | Ứng dụng OTT độc chiếm Microphone (`AudioSource.VOICE_COMMUNICATION`). Trên Android 10+ và iOS, ứng dụng thứ 3 chạy nền bị chặn hoặc chỉ nhận được luồng âm thanh rỗng (silent buffer). |
| **Chặn chụp âm thanh nội bộ (Internal Audio)** | Không áp dụng `AudioPlaybackCapture` cho đường truyền Baseband. | Các ứng dụng Zalo, Viber, Telegram, WhatsApp đặt cờ `allowAudioPlaybackCapture="false"` trong `AndroidManifest.xml`, chặn API `MediaProjection` thu âm thanh phát ra từ người bên kia. |
| **Mã hóa đầu cuối (E2EE)** | Không mã hóa đầu cuối ở tầng ứng dụng. | Mã hóa SRTP/DTLS (WebRTC); không thể bắt gói tin mạng (Packet Sniffing) để giải mã ra âm thanh. |

---

## 3. Ma Trận So Sánh Các Nhóm Giải Pháp Thực Thi

| Nhóm giải pháp | Hỗ trợ Gọi Thường | Hỗ trợ Zalo / Viber / Telegram / WhatsApp | Chất lượng âm thanh (2 chiều) | Độ phức tạp triển khai | Đánh giá thực tế |
| :--- | :---: | :---: | :--- | :--- | :--- |
| **1. Built-in OEM Dialer** (Samsung CSC VN/Thái, Xiaomi ROM Quốc tế/EU, OPPO) | Có (Tự động 100%) | Không | Rất cao (Direct Line) | Thấp (Cài đặt sẵn) | Tốt nhất cho gọi SIM thông thường, không có tiếng thông báo "Cuộc gọi đang được ghi âm". |
| **2. App Non-Root qua Accessibility** (Cube ACR, ACR Phone) | Có (Tùy dòng máy) | Có (Hạn chế) | Trung bình - Thấp (Phụ thuộc thu âm qua Mic/Loa ngoài) | Thấp | Trên Android 11+, chiều người bên kia nói thường bị nhỏ hoặc mất nếu dùng tai nghe. |
| **3. Can thiệp Hệ thống Android (Root + Magisk/LSPosed)** | Có (100%) | Có (100% cả tai nghe & loa trong) | Cao nhất (Digital Lossless 2 chiều) | Cao (Cần Unlock Bootloader & Root) | Hook trực tiếp vào `AudioFlinger` hoặc mở khóa `AudioPlaybackCapture` ở tầng Framework. |
| **4. Phần cứng MagSafe / Cảm biến rung xương (Piezoelectric)** (Plaud Note, Magmo...) | Có (100% iOS & Android) | Có (100% mọi ứng dụng) | Cao (Thu trực tiếp rung động khung máy & loa thoại) | Rất thấp (Gắn mặt lưng điện thoại) | Giải pháp **ổn định nhất cho iPhone** và máy Android không muốn Root. Hoạt động độc lập với OS. |
| **5. Giải pháp Desktop Companion (WASAPI Loopback)** | Không | Có (Khi nghe/gọi trên PC đồng bộ) | Cao nhất (Tách kênh Mic & System Audio riêng biệt) | Thấp | Phù hợp khi làm việc tại văn phòng với Zalo PC, Telegram Desktop, Viber, WhatsApp. |

---

## 4. Hướng Nghiên Cứu Tiếp Theo

1. **Nếu mục tiêu là sử dụng cá nhân/công việc ổn định trên cả iPhone & Android (Không can thiệp hệ thống)**: Đi sâu vào mục `docs/03_Giai_Phap_iOS_Va_Phan_Cung.md` và `docs/02_Giai_Phap_Phan_Mem_Android.md`.
2. **Nếu mục tiêu là lập trình / phát triển ứng dụng hoặc tinh chỉnh hệ thống Android**: Đi sâu vào `docs/01_Kien_Truc_Audio_OS_Rao_Can.md` và bộ script chẩn đoán ADB trong `scripts/`.
3. **Nếu mục tiêu là tự động hóa ghi âm + chuyển văn bản (Speech-to-Text) trên máy tính đồng bộ**: Xem prototype trong `prototypes/pc_companion_recorder/`.
