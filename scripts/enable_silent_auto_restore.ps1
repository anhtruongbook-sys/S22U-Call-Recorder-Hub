# Script 1-chạm cấp quyền Tự động Bật lại Trợ năng ngầm 100% (WRITE_SECURE_SETTINGS + USAGE_STATS)
# Dành cho Samsung Galaxy S22 Ultra (Non-Root, giữ nguyên Knox 0x0)

Write-Host "=== KICH HOAT TU DONG BAT LAI TRO NANG NGAM 100% (S22 ULTRA) ===" -ForegroundColor Cyan
adb devices

Write-Host "[1/2] Cap quyen WRITE_SECURE_SETTINGS (Tu bat lai Tro nang ngam sau khi thoat Ngan hang/VNeID)..." -ForegroundColor Yellow
adb shell pm grant com.s22ultra.callrecorder android.permission.WRITE_SECURE_SETTINGS

Write-Host "[2/2] Cap quyen PACKAGE_USAGE_STATS (Nhan dien chinh xac giay phut thoat App Ngan hang/VNeID)..." -ForegroundColor Yellow
adb shell appops set com.s22ultra.callrecorder GET_USAGE_STATS allow

Write-Host "HOAN TAT 100%! Tu bay gio App se tu dong TAT Tro nang khi mo Ngan hang/VNeID va tu dong BAT LAI ngam ngay khi thoat!" -ForegroundColor Green
