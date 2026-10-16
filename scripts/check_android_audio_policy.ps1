<#
.SYNOPSIS
    Kịch bản chẩn đoán cấu hình Âm thanh & Khả năng Ghi âm Cuộc gọi trên thiết bị Android qua ADB.
.DESCRIPTION
    Kiểm tra phiên bản Android, Mã vùng CSC (Samsung/Xiaomi), trạng thái quyền CAPTURE_AUDIO_OUTPUT,
    và phân tích luồng AudioFlinger / AudioPolicyService khi đang gọi thường hoặc gọi Zalo, Viber, Telegram, WhatsApp.
#>

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  CHẨN ĐOÁN KHẢ NĂNG GHI ÂM CUỘC GỌI ANDROID (CELLULAR & OTT)   " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Kiểm tra kết nối ADB
$adbCheck = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCheck) {
    Write-Host "[!] Chưa tìm thấy lệnh 'adb' trong PATH. Vui lòng cài đặt Android Platform Tools." -ForegroundColor Yellow
    exit 1
}

$devices = adb devices | Select-String -Pattern "\tdevice$"
if (-not $devices) {
    Write-Host "[!] Không phát hiện thiết bị Android nào đang kết nối ở chế độ USB Debugging." -ForegroundColor Yellow
    exit 1
}

# 2. Thông tin thiết bị & Mã vùng CSC
$manufacturer = (adb shell getprop ro.product.manufacturer).Trim()
$model = (adb shell getprop ro.product.model).Trim()
$androidVer = (adb shell getprop ro.build.version.release).Trim()
$sdkInt = (adb shell getprop ro.build.version.sdk).Trim()
$csc = (adb shell getprop ro.csc.sales_code).Trim()
if (-not $csc) { $csc = (adb shell getprop ril.sales_code).Trim() }

Write-Host "`n[1] THÔNG TIN THIẾT BỊ:" -ForegroundColor Green
Write-Host "  - Hãng sản xuất : $manufacturer"
Write-Host "  - Model         : $model"
Write-Host "  - Android       : $androidVer (API Level $sdkInt)"
if ($csc) {
    Write-Host "  - Mã vùng CSC   : $csc"
    if ($csc -in @("XXV", "THL", "INS")) {
        Write-Host "    => Thiết bị hỗ trợ GHI ÂM CUỘC GỌI GỐC (Native Auto Call Recording) trên trình gọi điện mặc định!" -ForegroundColor Cyan
    } else {
        Write-Host "    => Mã CSC ($csc) có thể bị ẩn tính năng ghi âm gốc. Có thể đổi CSC sang XXV (Việt Nam) qua SamFW Tool." -ForegroundColor Yellow
    }
}

# 3. Kiểm tra các ứng dụng OTT đã cài đặt và cờ allowAudioPlaybackCapture
Write-Host "`n[2] KIỂM TRA ỨNG DỤNG OTT (Zalo, Viber, Telegram, WhatsApp):" -ForegroundColor Green
$ottApps = @(
    @{ Name = "Zalo"; Package = "com.zing.zalo" },
    @{ Name = "Viber"; Package = "com.viber.voip" },
    @{ Name = "Telegram"; Package = "org.telegram.messenger" },
    @{ Name = "WhatsApp"; Package = "com.whatsapp" }
)

foreach ($app in $ottApps) {
    $installed = adb shell pm list packages $app.Package
    if ($installed) {
        $appopsRecord = (adb shell cmd appops get $app.Package RECORD_AUDIO | Out-String).Trim()
        Write-Host "  - [Đã cài] $($app.Name) ($($app.Package))"
        Write-Host "    Quyền RECORD_AUDIO: $appopsRecord" -ForegroundColor Gray
    } else {
        Write-Host "  - [Chưa cài] $($app.Name) ($($app.Package))" -ForegroundColor DarkGray
    }
}

# 4. Kiểm tra trạng thái AudioFlinger / AudioPolicy đang hoạt động
Write-Host "`n[3] TRẠNG THÁI LUỒNG THU ÂM HIỆN TẠI (AudioPolicyService Active Inputs):" -ForegroundColor Green
$audioDump = adb shell dumpsys media.audio_policy | Select-String -Pattern "AudioSource|VOICE_COMMUNICATION|VOICE_CALL|VOICE_RECOGNITION|Concurrent" -Context 0,2
if ($audioDump) {
    $audioDump | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "  (Hiện không có phiên thu âm cuộc gọi nào đang mở)" -ForegroundColor Gray
}

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "Gợi ý: Để cấp quyền GHI ÂM NỘI BỘ cho ứng dụng hỗ trợ (ví dụ Cube ACR):" -ForegroundColor Yellow
Write-Host "  adb shell cmd appops set com.catalinagroup.callrecorder PROJECT_MEDIA allow"
Write-Host "  adb shell pm grant com.catalinagroup.callrecorder android.permission.RECORD_AUDIO"
Write-Host "=================================================================" -ForegroundColor Cyan
