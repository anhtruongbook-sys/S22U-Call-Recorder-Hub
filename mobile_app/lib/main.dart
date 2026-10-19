import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const S22UltraCallRecorderApp());
}

class S22UltraCallRecorderApp extends StatefulWidget {
  const S22UltraCallRecorderApp({super.key});

  @override
  State<S22UltraCallRecorderApp> createState() => _S22UltraCallRecorderAppState();
}

class _S22UltraCallRecorderAppState extends State<S22UltraCallRecorderApp> {
  bool _isDark = true;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'S22U Call Recorder Hub',
      debugShowCheckedModeBanner: false,
      themeMode: _isDark ? ThemeMode.dark : ThemeMode.light,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.light,
        colorSchemeSeed: const Color(0xFF0D47A1),
        scaffoldBackgroundColor: const Color(0xFFF5F7FA),
        dividerTheme: const DividerThemeData(thickness: 0.8, space: 1),
      ),
      darkTheme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorSchemeSeed: const Color(0xFF38BDF8),
        scaffoldBackgroundColor: const Color(0xFF0B1120),
        dividerTheme: const DividerThemeData(thickness: 0.8, space: 1),
      ),
      home: RecorderHomeScreen(
        isDark: _isDark,
        onToggleTheme: () => setState(() => _isDark = !_isDark),
      ),
    );
  }
}

class RecorderHomeScreen extends StatefulWidget {
  final bool isDark;
  final VoidCallback onToggleTheme;

  const RecorderHomeScreen({
    super.key,
    required this.isDark,
    required this.onToggleTheme,
  });

  @override
  State<RecorderHomeScreen> createState() => _RecorderHomeScreenState();
}

class _RecorderHomeScreenState extends State<RecorderHomeScreen> with WidgetsBindingObserver {
  static const MethodChannel _channel = MethodChannel('com.s22ultra.callrecorder/native');

  Map<String, dynamic> _status = {};
  Map<String, dynamic> _security = {};
  List<Map<String, dynamic>> _recordings = [];
  String _selectedFilter = 'ALL';
  String _manualAppTag = 'Zalo';
  Timer? _pollTimer;

  // Trạng thái Khóa Bảo Mật (App Lock)
  bool _isLocked = false;
  bool _initialLockChecked = false;
  bool _isBiometricPromptRunning = false;
  int _lastUnlockTimestampMs = 0;

  // Chế độ nhập trên màn hình khóa: 'BIOMETRIC' | 'PIN6' | 'PASSWORD'
  String _activeUnlockMode = 'PIN6';
  String _pinInput = '';
  final TextEditingController _unlockPasswordCtrl = TextEditingController();
  bool _obscureUnlockPassword = true;
  String _unlockErrorMsg = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initLoadAndLockCheck();
    _startUiTimer();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _stopUiTimer();
    _unlockPasswordCtrl.dispose();
    super.dispose();
  }

  Future<void> _initLoadAndLockCheck() async {
    await _refreshAll();
    if (!_initialLockChecked) {
      _initialLockChecked = true;
      final lockEnabled = _security['lockEnabled'] == true;
      if (lockEnabled) {
        _engageAppLock(autoTriggerBiometric: true);
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshAll();
      _startUiTimer();
      // Nếu ứng dụng đang bị khóa và đã bật vân tay làm mặc định -> tự động bật quét vân tay khi quay lại App
      final now = DateTime.now().millisecondsSinceEpoch;
      if (_isLocked &&
          !_isBiometricPromptRunning &&
          (now - _lastUnlockTimestampMs > 1800) &&
          _security['biometricEnabled'] == true) {
        Future.delayed(const Duration(milliseconds: 250), () {
          if (mounted && _isLocked && !_isBiometricPromptRunning) {
            _triggerBiometricUnlock();
          }
        });
      }
    } else if (state == AppLifecycleState.paused || state == AppLifecycleState.hidden) {
      // Khi chuyển sang ứng dụng khác hoặc tắt màn hình:
      // 1. Dừng 100% Timer giao diện -> 0.0% CPU / 0% Pin!
      _stopUiTimer();

      // 2. Tự động khóa lại App ngay lập tức nếu đã bật khóa bảo mật (trừ lúc đang hiện bảng quét vân tay)
      final now = DateTime.now().millisecondsSinceEpoch;
      final lockEnabled = _security['lockEnabled'] == true;
      final autoLockOnSwitch = _security['autoLockOnSwitch'] != false;
      if (lockEnabled &&
          autoLockOnSwitch &&
          !_isBiometricPromptRunning &&
          (now - _lastUnlockTimestampMs > 1500)) {
        setState(() {
          _isLocked = true;
          _pinInput = '';
          _unlockPasswordCtrl.clear();
          _unlockErrorMsg = '';
          _selectDefaultUnlockMode();
        });
      }
    }
  }

  void _selectDefaultUnlockMode() {
    final bioEnabled = _security['biometricEnabled'] == true;
    final hasPin6 = _security['hasPin6'] == true;
    final hasPassword = _security['hasPassword'] == true;
    if (bioEnabled) {
      _activeUnlockMode = hasPin6 ? 'PIN6' : (hasPassword ? 'PASSWORD' : 'BIOMETRIC');
    } else if (hasPin6) {
      _activeUnlockMode = 'PIN6';
    } else if (hasPassword) {
      _activeUnlockMode = 'PASSWORD';
    }
  }

  void _engageAppLock({bool autoTriggerBiometric = false}) {
    setState(() {
      _isLocked = true;
      _pinInput = '';
      _unlockPasswordCtrl.clear();
      _unlockErrorMsg = '';
      _selectDefaultUnlockMode();
    });
    if (autoTriggerBiometric && _security['biometricEnabled'] == true) {
      Future.delayed(const Duration(milliseconds: 300), () {
        if (mounted && _isLocked) {
          _triggerBiometricUnlock();
        }
      });
    }
  }

  Future<void> _triggerBiometricUnlock() async {
    if (_isBiometricPromptRunning) return;
    setState(() {
      _isBiometricPromptRunning = true;
      _unlockErrorMsg = '';
    });
    try {
      final ok = await _channel.invokeMethod('authenticateBiometric');
      final now = DateTime.now().millisecondsSinceEpoch;
      if (ok == true && mounted) {
        _lastUnlockTimestampMs = now;
        setState(() {
          _isLocked = false;
          _pinInput = '';
          _unlockPasswordCtrl.clear();
          _unlockErrorMsg = '';
        });
      }
    } catch (_) {
    } finally {
      Future.delayed(const Duration(milliseconds: 400), () {
        if (mounted) {
          setState(() {
            _isBiometricPromptRunning = false;
          });
        }
      });
    }
  }

  Future<void> _verifyPin6Unlock(String pin) async {
    try {
      final ok = await _channel.invokeMethod('verifyPin6', {'pin': pin});
      if (ok == true && mounted) {
        _lastUnlockTimestampMs = DateTime.now().millisecondsSinceEpoch;
        setState(() {
          _isLocked = false;
          _pinInput = '';
          _unlockErrorMsg = '';
        });
      } else if (mounted) {
        setState(() {
          _pinInput = '';
          _unlockErrorMsg = 'Mã PIN 6 số không chính xác!';
        });
      }
    } catch (_) {}
  }

  Future<void> _verifyPasswordUnlock() async {
    final pwd = _unlockPasswordCtrl.text.trim();
    if (pwd.isEmpty) {
      setState(() => _unlockErrorMsg = 'Vui lòng nhập mật khẩu');
      return;
    }
    try {
      final ok = await _channel.invokeMethod('verifyPassword', {'password': pwd});
      if (ok == true && mounted) {
        _lastUnlockTimestampMs = DateTime.now().millisecondsSinceEpoch;
        setState(() {
          _isLocked = false;
          _unlockPasswordCtrl.clear();
          _unlockErrorMsg = '';
        });
      } else if (mounted) {
        setState(() {
          _unlockErrorMsg = 'Mật khẩu không chính xác!';
        });
      }
    } catch (_) {}
  }

  void _startUiTimer() {
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(const Duration(seconds: 2), (_) => _refreshStatusOnly());
  }

  void _stopUiTimer() {
    _pollTimer?.cancel();
    _pollTimer = null;
  }

  Future<void> _refreshAll() async {
    await _refreshStatusOnly();
    await _refreshRecordings();
  }

  Future<void> _refreshStatusOnly() async {
    try {
      final res = await _channel.invokeMethod('getSystemStatus');
      if (res is Map && mounted) {
        final map = Map<String, dynamic>.from(res);
        final secRaw = map['security'];
        setState(() {
          _status = map;
          if (secRaw is Map) {
            _security = Map<String, dynamic>.from(secRaw);
          }
        });
      }
    } catch (_) {}
  }

  Future<void> _refreshRecordings() async {
    try {
      final res = await _channel.invokeMethod('listRecordings');
      if (res is List && mounted) {
        setState(() {
          _recordings = res.map((e) => Map<String, dynamic>.from(e as Map)).toList();
        });
      }
    } catch (_) {}
  }

  Future<void> _updateDspSettings({
    bool? autoRecordOtt,
    double? remoteGainBoost,
    bool? autoBoostVolume,
  }) async {
    final newAutoOtt = autoRecordOtt ?? (_status['autoRecordOttEnabled'] == true);
    final newGain = remoteGainBoost ?? ((_status['remoteGainBoost'] as num?)?.toDouble() ?? 2.2);
    final newBoostVol = autoBoostVolume ?? (_status['autoBoostVolume'] == true);

    await _channel.invokeMethod('updateSettings', {
      'autoRecordOttEnabled': newAutoOtt,
      'remoteGainBoost': newGain,
      'autoBoostVolume': newBoostVol,
    });
    await _refreshStatusOnly();
  }

  Future<void> _toggleManualRecording() async {
    final isRec = _status['isRecording'] == true;
    if (isRec) {
      await _channel.invokeMethod('stopRecording');
      await _refreshAll();
    } else {
      await _channel.invokeMethod('startManualRecording', {'appTag': _manualAppTag});
      await _refreshStatusOnly();
    }
  }

  List<Map<String, dynamic>> get _filteredRecordings {
    if (_selectedFilter == 'ALL') return _recordings;
    if (_selectedFilter == 'SIM') {
      return _recordings
          .where((r) => ((r['sourceType'] ?? r['category'] ?? '').toString().contains('SIM')))
          .toList();
    }
    return _recordings
        .where((r) => ((r['sourceType'] ?? r['category'] ?? '').toString().contains('OTT')))
        .toList();
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';
  }

  @override
  Widget build(BuildContext context) {
    // Nếu đang ở trạng thái Khóa Bảo Mật -> Hiển thị màn hình khóa toàn phần che kín nội dung
    if (_isLocked) {
      return _buildLockScreen(context);
    }

    final guardEnabled = _status['accessibilityEnabled'] == true;
    final micGranted = _status['micPermissionGranted'] == true;
    final storageGranted = _status['storagePermissionGranted'] == true;
    final isRecording = _status['isRecording'] == true;
    final peakAmp = (_status['peakAmplitude'] as num?)?.toInt() ?? 0;
    final autoOtt = _status['autoRecordOttEnabled'] ?? true;
    final autoVol = _status['autoBoostVolume'] ?? false;
    final gainBoost = ((_status['remoteGainBoost'] as num?)?.toDouble() ?? 2.2).clamp(1.0, 4.0);
    final model = (_status['model'] ?? 'SM-S908U').toString();
    final androidVer = (_status['androidRelease'] ?? '16').toString();
    final installedOtt = (_status['installedOttApps'] as List?)?.map((e) => e.toString()).toList() ?? [];
    final lockEnabled = _security['lockEnabled'] == true;

    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          titleSpacing: 14,
          title: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'S22 Ultra Call Recorder Hub',
                style: TextStyle(fontSize: 16.0, fontWeight: FontWeight.w700),
              ),
              Text(
                '$model • Android $androidVer • Zero-Polling 0% Pin',
                style: TextStyle(
                  fontSize: 11.2,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
          actions: [
            if (lockEnabled)
              IconButton(
                tooltip: 'Khóa ứng dụng ngay',
                icon: const Icon(Icons.lock_outline, color: Colors.amber),
                onPressed: () => _engageAppLock(autoTriggerBiometric: false),
              ),
            IconButton(
              tooltip: 'Làm mới danh sách',
              icon: const Icon(Icons.refresh),
              onPressed: _refreshAll,
            ),
            IconButton(
              tooltip: 'Đổi giao diện Sáng/Tối',
              icon: Icon(widget.isDark ? Icons.light_mode : Icons.dark_mode),
              onPressed: widget.onToggleTheme,
            ),
          ],
          bottom: const TabBar(
            dividerColor: Colors.transparent,
            tabs: [
              Tab(icon: Icon(Icons.library_music_outlined, size: 20), text: 'Bản Ghi Âm'),
              Tab(icon: Icon(Icons.graphic_eq, size: 20), text: 'Cấu Hình DSP'),
              Tab(icon: Icon(Icons.security_outlined, size: 20), text: 'Cài Đặt & Khóa'),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            _buildRecordingsTab(
              guardEnabled: guardEnabled,
              micGranted: micGranted,
              isRecording: isRecording,
              peakAmp: peakAmp,
            ),
            _buildDspConfigTab(
              autoOtt: autoOtt,
              autoVol: autoVol,
              gainBoost: gainBoost,
              installedOtt: installedOtt,
            ),
            _buildSettingsAndSecurityTab(
              guardEnabled: guardEnabled,
              micGranted: micGranted,
              storageGranted: storageGranted,
            ),
          ],
        ),
      ),
    );
  }

  // ==================== MÀN HÌNH KHÓA BẢO MẬT (VÂN TAY MẶC ĐỊNH / MÃ PIN 6 SỐ / PASSWORD) ====================
  Widget _buildLockScreen(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final hasPin6 = _security['hasPin6'] == true;
    final hasPassword = _security['hasPassword'] == true;
    final bioEnabled = _security['biometricEnabled'] == true;

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Khóa Bảo Mật S22U Recorder',
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
        ),
        centerTitle: true,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const SizedBox(height: 8),
              Icon(
                bioEnabled ? Icons.fingerprint : Icons.lock_person_outlined,
                size: 58,
                color: cs.primary,
              ),
              const SizedBox(height: 10),
              Text(
                bioEnabled
                    ? 'Xác thực Vân tay (Mặc định) hoặc Mã khóa'
                    : 'Nhập Mã PIN 6 số hoặc Mật khẩu để mở khóa',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              Text(
                'Dịch vụ tự động ghi âm ngầm vẫn hoạt động 100% khi khóa màn hình này',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
              ),
              const SizedBox(height: 16),

              // Nút quét Vân tay Siêu âm S22 Ultra (Mặc định khi bật sinh trắc học)
              if (bioEnabled)
                Padding(
                  padding: const EdgeInsets.only(bottom: 16),
                  child: SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: _triggerBiometricUnlock,
                      icon: const Icon(Icons.fingerprint, size: 24),
                      label: const Text(
                        'Chạm Mở Khóa Bằng Vân Tay (Mặc Định)',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      style: FilledButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 13),
                      ),
                    ),
                  ),
                ),

              // Chuyển đổi giữa Mã PIN 6 số và Password nếu người dùng cài cả hai
              if (hasPin6 && hasPassword)
                Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(
                        value: 'PIN6',
                        label: Text('Mã PIN 6 số'),
                        icon: Icon(Icons.dialpad, size: 18),
                      ),
                      ButtonSegment(
                        value: 'PASSWORD',
                        label: Text('Mật khẩu'),
                        icon: Icon(Icons.password, size: 18),
                      ),
                    ],
                    selected: {_activeUnlockMode == 'PASSWORD' ? 'PASSWORD' : 'PIN6'},
                    onSelectionChanged: (s) {
                      setState(() {
                        _activeUnlockMode = s.first;
                        _unlockErrorMsg = '';
                        _pinInput = '';
                      });
                    },
                  ),
                ),

              if (_unlockErrorMsg.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text(
                    _unlockErrorMsg,
                    style: const TextStyle(
                      color: Colors.redAccent,
                      fontWeight: FontWeight.w600,
                      fontSize: 13,
                    ),
                  ),
                ),

              // Giao diện 1: Nhập Mã PIN 6 Số
              if ((_activeUnlockMode == 'PIN6' && hasPin6) || (!hasPassword && hasPin6)) ...[
                const Text(
                  'Nhập Mã PIN 6 chữ số:',
                  style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: List.generate(6, (index) {
                    final filled = index < _pinInput.length;
                    return Container(
                      margin: const EdgeInsets.symmetric(horizontal: 7),
                      width: 16,
                      height: 16,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: filled ? cs.primary : cs.surfaceContainerHighest,
                      ),
                    );
                  }),
                ),
                const SizedBox(height: 20),
                _buildPinKeypad(),
              ] else if (hasPassword) ...[
                // Giao diện 2: Nhập Mật khẩu (Password)
                TextField(
                  controller: _unlockPasswordCtrl,
                  obscureText: _obscureUnlockPassword,
                  onSubmitted: (_) => _verifyPasswordUnlock(),
                  decoration: InputDecoration(
                    labelText: 'Nhập mật khẩu bảo mật',
                    prefixIcon: const Icon(Icons.key),
                    suffixIcon: IconButton(
                      icon: Icon(_obscureUnlockPassword ? Icons.visibility : Icons.visibility_off),
                      onPressed: () => setState(() => _obscureUnlockPassword = !_obscureUnlockPassword),
                    ),
                  ),
                ),
                const SizedBox(height: 14),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: _verifyPasswordUnlock,
                    icon: const Icon(Icons.lock_open),
                    label: const Text('Mở Khóa Bằng Mật Khẩu'),
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 13),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPinKeypad() {
    final keys = [
      ['1', '2', '3'],
      ['4', '5', '6'],
      ['7', '8', '9'],
      ['CLEAR', '0', 'DEL'],
    ];
    return Column(
      children: keys.map((row) {
        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: row.map((k) {
              if (k == 'CLEAR') {
                return SizedBox(
                  width: 76,
                  height: 52,
                  child: TextButton(
                    onPressed: () => setState(() {
                      _pinInput = '';
                      _unlockErrorMsg = '';
                    }),
                    child: const Text('Xóa hết', style: TextStyle(fontSize: 12.5)),
                  ),
                );
              }
              if (k == 'DEL') {
                return SizedBox(
                  width: 76,
                  height: 52,
                  child: IconButton(
                    onPressed: () {
                      if (_pinInput.isNotEmpty) {
                        setState(() {
                          _pinInput = _pinInput.substring(0, _pinInput.length - 1);
                          _unlockErrorMsg = '';
                        });
                      }
                    },
                    icon: const Icon(Icons.backspace_outlined),
                  ),
                );
              }
              return SizedBox(
                width: 76,
                height: 52,
                child: ElevatedButton(
                  onPressed: () {
                    if (_pinInput.length < 6) {
                      final next = _pinInput + k;
                      setState(() {
                        _pinInput = next;
                        _unlockErrorMsg = '';
                      });
                      if (next.length == 6) {
                        _verifyPin6Unlock(next);
                      }
                    }
                  },
                  child: Text(
                    k,
                    style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                  ),
                ),
              );
            }).toList(),
          ),
        );
      }).toList(),
    );
  }

  /// TAB 1: DANH SÁCH BẢN GHI ÂM (GIAO DIỆN PHẲNG - RULE 14)
  Widget _buildRecordingsTab({
    required bool guardEnabled,
    required bool micGranted,
    required bool isRecording,
    required int peakAmp,
  }) {
    final items = _filteredRecordings;
    final playingPath = (_status['currentlyPlayingPath'] ?? '').toString();
    final cs = Theme.of(context).colorScheme;

    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      children: [
        if (!micGranted)
          Container(
            margin: const EdgeInsets.only(bottom: 12),
            padding: const EdgeInsets.fromLTRB(12, 10, 10, 10),
            decoration: BoxDecoration(
              color: Colors.amber.withOpacity(0.10),
              border: Border(
                left: BorderSide(color: Colors.amber.shade600, width: 3.5),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Cần cấp quyền Microphone & Lưu trữ',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13.5),
                ),
                const SizedBox(height: 6),
                FilledButton.tonalIcon(
                  onPressed: () async {
                    await _channel.invokeMethod('requestPermissions');
                    await _refreshAll();
                  },
                  icon: const Icon(Icons.mic, size: 16),
                  label: const Text('Cấp quyền Mic & Bộ nhớ'),
                ),
              ],
            ),
          ),

        // CHỈ HIỆN KHI CẦU NỐI THU ÂM ZALO CHƯA BẬT (BẬT XONG TỰ ĐỘNG ẨN 100% NGAY LẬP TỨC)
        if (_status['accessibilityEnabled'] != true)
          Container(
            margin: const EdgeInsets.only(bottom: 12),
            padding: const EdgeInsets.fromLTRB(12, 10, 10, 10),
            decoration: BoxDecoration(
              color: Colors.redAccent.withOpacity(0.14),
              border: const Border(
                left: BorderSide(color: Colors.redAccent, width: 4.0),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '⚠️ CẦU NỐI ZALO (S22U Call Bridge) ĐANG TẮT (Chế độ Tàng hình App Ngân hàng)!\n'
                  '• Cầu nối tự động TẮT trong 0ms khi mở App Ngân hàng / VNeID để không bao giờ báo lỗi.\n'
                  '• Trước khi gọi Zalo/SIM, hãy bấm "Bật S22U Call Bridge Ngay" (hoặc chạy lệnh Brevent 1 lần để App tự động Bật/Tắt ngầm 100%):',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5, height: 1.4),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    FilledButton.icon(
                      style: FilledButton.styleFrom(
                        backgroundColor: Colors.redAccent,
                        foregroundColor: Colors.white,
                        visualDensity: VisualDensity.compact,
                      ),
                      onPressed: () async {
                        await _channel.invokeMethod('openAccessibilitySettings');
                        await _refreshStatusOnly();
                      },
                      icon: const Icon(Icons.accessibility_new, size: 16),
                      label: const Text('Bật S22U Call Bridge Ngay'),
                    ),
                    OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        visualDensity: VisualDensity.compact,
                      ),
                      onPressed: () async {
                        await Clipboard.setData(
                          const ClipboardData(
                            text:
                                'pm grant com.s22ultra.callrecorder android.permission.WRITE_SECURE_SETTINGS; appops set com.s22ultra.callrecorder ACCESS_RESTRICTED_SETTINGS allow; settings put secure enabled_accessibility_services com.s22ultra.callrecorder/com.s22ultra.callrecorder.OTTCallAccessibilityService; settings put secure accessibility_enabled 1',
                          ),
                        );
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                              content: Text('Đã copy lệnh Brevent mở khóa tự động 100%!'),
                            ),
                          );
                        }
                      },
                      icon: const Icon(Icons.copy, size: 15),
                      label: const Text('Copy Lệnh Brevent'),
                    ),
                  ],
                ),
              ],
            ),
          ),

        // Bảng điều khiển Thu âm & Trạng thái Ngủ Đông 0% Pin
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: isRecording
                ? Colors.red.withOpacity(0.12)
                : cs.surfaceContainerHighest.withOpacity(0.45),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: isRecording ? Colors.redAccent : cs.outlineVariant.withOpacity(0.4),
              width: 1,
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    isRecording ? Icons.fiber_manual_record : Icons.eco_outlined,
                    color: isRecording ? Colors.redAccent : Colors.greenAccent,
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      isRecording
                          ? 'ĐANG GHI ÂM (${_status['currentAppTag'] ?? 'OTT'}) • Peak: $peakAmp'
                          : 'Ngủ Đông 0% Pin (Tự kích hoạt kể cả khi đã tắt App)',
                      style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.5),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Row(
                children: [
                  Expanded(
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _manualAppTag,
                        isDense: true,
                        items: const [
                          DropdownMenuItem(value: 'Zalo', child: Text('Kênh: Zalo Call')),
                          DropdownMenuItem(value: 'Viber', child: Text('Kênh: Viber Call')),
                          DropdownMenuItem(value: 'Telegram', child: Text('Kênh: Telegram Call')),
                          DropdownMenuItem(value: 'WhatsApp', child: Text('Kênh: WhatsApp Call')),
                          DropdownMenuItem(value: 'Messenger', child: Text('Kênh: Messenger Call')),
                          DropdownMenuItem(value: 'Ghi_Am_Nhanh', child: Text('Kênh: Ghi âm trực tiếp')),
                        ],
                        onChanged: isRecording
                            ? null
                            : (v) {
                                if (v != null) setState(() => _manualAppTag = v);
                              },
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: isRecording ? Colors.redAccent : null,
                    ),
                    onPressed: _toggleManualRecording,
                    icon: Icon(isRecording ? Icons.stop : Icons.fiber_manual_record, size: 18),
                    label: Text(isRecording ? 'Dừng & Lưu' : 'Ghi Thử Ngay'),
                  ),
                ],
              ),
            ],
          ),
        ),

        const SizedBox(height: 14),

        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              _buildFilterChip('ALL', 'Tất cả (${_recordings.length})'),
              const SizedBox(width: 8),
              _buildFilterChip(
                'OTT',
                'Zalo / Viber / Telegram / WA (${_recordings.where((r) => (r['sourceType'] ?? '').toString().contains('OTT')).length})',
              ),
              const SizedBox(width: 8),
              _buildFilterChip(
                'SIM',
                'SIM Thường Samsung (${_recordings.where((r) => (r['sourceType'] ?? '').toString().contains('SIM')).length})',
              ),
            ],
          ),
        ),

        const SizedBox(height: 10),
        const Divider(),

        if (items.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 40),
            child: Column(
              children: [
                Icon(Icons.folder_open, size: 44, color: cs.onSurfaceVariant.withOpacity(0.5)),
                const SizedBox(height: 10),
                Text(
                  'Chưa có bản ghi âm nào trong danh mục này.',
                  style: TextStyle(color: cs.onSurfaceVariant, fontSize: 13.5),
                ),
                const SizedBox(height: 4),
                Text(
                  'Các file ghi âm được lưu vĩnh viễn tại /Download/S22U_Call_Recordings/',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: cs.onSurfaceVariant.withOpacity(0.75), fontSize: 12),
                ),
              ],
            ),
          )
        else
          ...items.map((item) => _buildFlatRecordingRow(item, playingPath, cs)),
      ],
    );
  }

  Widget _buildFilterChip(String key, String label) {
    final selected = _selectedFilter == key;
    return ChoiceChip(
      label: Text(label, style: const TextStyle(fontSize: 12.5)),
      selected: selected,
      onSelected: (_) => setState(() => _selectedFilter = key),
    );
  }

  Widget _buildFlatRecordingRow(
    Map<String, dynamic> item,
    String playingPath,
    ColorScheme cs,
  ) {
    final path = (item['path'] ?? '').toString();
    final name = (item['name'] ?? 'Unknown').toString();
    final appTag = (item['appTag'] ?? item['source'] ?? 'Call').toString();
    final sourceType = (item['sourceType'] ?? item['category'] ?? '').toString();
    final dateFormatted = (item['dateFormatted'] ?? '').toString();
    final rawBytes = (item['sizeBytes'] as num?)?.toInt();
    final rawKB = (item['sizeKB'] as num?)?.toInt();
    final sizeBytes = rawBytes ?? ((rawKB ?? 0) * 1024);
    final isPlaying = playingPath == path && path.isNotEmpty;
    final isSim = sourceType.contains('SIM');

    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 4),
      decoration: BoxDecoration(
        border: Border(
          bottom: BorderSide(color: cs.outlineVariant.withOpacity(0.35), width: 0.8),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                isSim ? Icons.sim_card_outlined : Icons.wifi_calling_3_outlined,
                size: 18,
                color: isSim ? Colors.amber : cs.primary,
              ),
              const SizedBox(width: 6),
              Text(
                appTag.toUpperCase(),
                style: TextStyle(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w700,
                  color: isSim ? Colors.amber : cs.primary,
                ),
              ),
              const Spacer(),
              Text(
                '$dateFormatted • ${_formatBytes(sizeBytes)}',
                style: TextStyle(fontSize: 11.5, color: cs.onSurfaceVariant),
              ),
            ],
          ),
          const SizedBox(height: 5),
          Text(
            name,
            style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13.5),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              FilledButton.tonalIcon(
                style: FilledButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                ),
                onPressed: () async {
                  if (isPlaying) {
                    await _channel.invokeMethod('stopPlayback');
                  } else {
                    await _channel.invokeMethod('playRecording', {'path': path});
                  }
                  await _refreshStatusOnly();
                },
                icon: Icon(isPlaying ? Icons.stop : Icons.play_arrow, size: 16),
                label: Text(isPlaying ? 'Dừng nghe' : 'Nghe (Lọc ồn)', style: const TextStyle(fontSize: 12)),
              ),
              OutlinedButton.icon(
                style: OutlinedButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                ),
                onPressed: () async {
                  await _channel.invokeMethod('shareRecording', {'path': path});
                },
                icon: const Icon(Icons.share_outlined, size: 15),
                label: const Text('Chia sẻ', style: TextStyle(fontSize: 12)),
              ),
              TextButton.icon(
                style: TextButton.styleFrom(
                  visualDensity: VisualDensity.compact,
                  foregroundColor: Colors.redAccent,
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                ),
                onPressed: () async {
                  await _channel.invokeMethod('deleteRecording', {'path': path});
                  await _refreshAll();
                },
                icon: const Icon(Icons.delete_outline, size: 15),
                label: const Text('Xóa', style: TextStyle(fontSize: 12)),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// TAB 2: CẤU HÌNH DSP & KIẾN TRÚC KHÔNG TRỢ NĂNG (ĐÃ XÓA 100% MỤC TRỢ NĂNG CŨ)
  Widget _buildDspConfigTab({
    required bool autoOtt,
    required bool autoVol,
    required double gainBoost,
    required List<String> installedOtt,
  }) {
    final cs = Theme.of(context).colorScheme;
    final actualBoostMultiplier = (1.35 + (gainBoost - 1.0) * 0.48).toStringAsFixed(1);

    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      children: [
        const Text(
          'Động Cơ Lọc Ồn Giọng Nói 2 Tầng (260Hz–3400Hz • Êm Dịu Không Sôi Nền)',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 6),
        Container(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
          decoration: BoxDecoration(
            color: Colors.green.withOpacity(0.10),
            border: const Border(left: BorderSide(color: Colors.greenAccent, width: 3.5)),
          ),
          child: const Text(
            '• GIỮ NGUYÊN BỘ KHỬ TIẾNG VANG PHẦN CỨNG SNAPDRAGON: Tuyệt đối không tắt AcousticEchoCanceler phần cứng của máy, kết hợp NoiseSuppressor giúp cuộc gọi và bản thu êm ru.\n'
            '• BỘ NÉN TẠP ÂM NỀN (Downward Expander -13 dB): Tự động hạ âm lượng nền tĩnh xuống 0.22x khi không có người nói và cắt sạch tạp âm ngoài dải giọng nói (< 260Hz & > 3400Hz).',
            style: TextStyle(fontSize: 12.5, height: 1.45),
          ),
        ),
        const SizedBox(height: 8),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Tự động ghi âm cuộc gọi OTT & SIM (24/7)'),
          subtitle: const Text(
            'Tự động thức dậy từ chế độ Ngủ Đông (0.0% CPU) kể cả khi đã vuốt đóng App.',
          ),
          value: autoOtt,
          onChanged: (v) => _updateDspSettings(autoRecordOtt: v),
        ),
        const Divider(),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Hỗ trợ nâng nhẹ loa thoại lên 65% (Mặc định: TẮT)'),
          subtitle: const Text(
            'Khuyên để TẮT để giữ nguyên âm lượng loa nghe cuộc gọi êm ái mặc định của bạn.',
          ),
          value: autoVol,
          onChanged: (v) => _updateDspSettings(autoBoostVolume: v),
        ),
        const Divider(),
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Expanded(
              child: Text(
                'Độ nâng giọng nói đầu dây bên kia (Voice-Band Gain)',
                style: TextStyle(fontWeight: FontWeight.w600, fontSize: 13.5),
              ),
            ),
            Text(
              '${actualBoostMultiplier}x (Lọc ồn -13dB)',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: cs.primary,
                fontSize: 14,
              ),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          'Khi không có người nói: Tự động nén tạp âm nền xuống 0.22x (-13 dB, êm ru không sôi).\n'
          'Khi có giọng nói (260Hz–3400Hz): Nâng giọng bên kia lên ${actualBoostMultiplier}x và giữ giọng bạn ở mức êm 0.88x.',
          style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
        ),
        Slider(
          value: gainBoost,
          min: 1.0,
          max: 4.0,
          divisions: 15,
          label: '${actualBoostMultiplier}x',
          onChanged: (v) => _updateDspSettings(remoteGainBoost: v),
        ),
        const Divider(),
        const SizedBox(height: 12),
        const Text(
          'Ứng dụng OTT đã nhận diện trên chiếc S22 Ultra này:',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
        ),
        const SizedBox(height: 8),
        if (installedOtt.isEmpty)
          Text(
            'Đang quét danh sách Zalo, Viber, Telegram, WhatsApp, Messenger...',
            style: TextStyle(fontSize: 12.5, color: cs.onSurfaceVariant),
          )
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: installedOtt
                .map(
                  (app) => Chip(
                    avatar: const Icon(Icons.check_circle_outline, size: 16),
                    label: Text(app, style: const TextStyle(fontSize: 12.5)),
                  ),
                )
                .toList(),
          ),
        const SizedBox(height: 14),
        Container(
          padding: const EdgeInsets.only(left: 12, top: 8, bottom: 8),
          decoration: BoxDecoration(
            border: Border(left: BorderSide(color: cs.primary, width: 3)),
          ),
          child: Text(
            'Trạng thái Động cơ Ngầm: ${_status['lastDetectedApp'] ?? 'Đang Ngủ Đông (0.0% CPU)'}\n'
            'Thư mục lưu vĩnh viễn (Xóa App không mất): /storage/emulated/0/Download/S22U_Call_Recordings/',
            style: const TextStyle(fontSize: 12.5, height: 1.45),
          ),
        ),
      ],
    );
  }

  /// TAB 3: CÀI ĐẶT HỆ THỐNG & KHÓA BẢO MẬT (PASSWORD / MÃ PIN 6 SỐ / VÂN TAY MẶC ĐỊNH)
  Widget _buildSettingsAndSecurityTab({
    required bool guardEnabled,
    required bool micGranted,
    required bool storageGranted,
  }) {
    final cs = Theme.of(context).colorScheme;
    final disableMode = (_status['disableMode'] ?? 'NONE').toString();
    final usageStatsGranted = _status['usageStatsGranted'] == true;
    final overlayGranted = _status['overlayPermissionGranted'] == true;
    final notifListenerGranted = _status['notificationListenerGranted'] == true;

    final lockEnabled = _security['lockEnabled'] == true;
    final hasPassword = _security['hasPassword'] == true;
    final hasPin6 = _security['hasPin6'] == true;
    final bioAvailable = _security['biometricAvailable'] == true;
    final bioEnabled = _security['biometricEnabled'] == true;
    final autoLockOnSwitch = _security['autoLockOnSwitch'] != false;

    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      children: [
        // ==================== PHẦN 1: BẢO MẬT & KHÓA ỨNG DỤNG ====================
        const Text(
          'Bảo Mật & Khóa Ứng Dụng (App Lock)',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 6),
        Container(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
          decoration: BoxDecoration(
            color: cs.primaryContainer.withOpacity(0.25),
            border: Border(left: BorderSide(color: cs.primary, width: 3.5)),
          ),
          child: Text(
            lockEnabled
                ? '• Trạng thái Khóa Bảo Mật: ĐANG BẬT\n'
                  '• Phương thức mở khóa Mặc định: ${bioEnabled ? "Sinh trắc học Vân tay S22 Ultra (Mặc định)" : (hasPin6 ? "Mã PIN 6 số" : "Mật khẩu (Password)")}\n'
                  '• Tự động khóa lại ngay khi chuyển sang ứng dụng khác: ${autoLockOnSwitch ? "ĐANG BẬT" : "TẮT"}'
                : '• Chưa bật khóa bảo mật. Hãy đặt Mật khẩu (Password) hoặc Mã PIN 6 số (nhập 2 lần xác nhận) bên dưới để kích hoạt.',
            style: const TextStyle(fontSize: 12.5, height: 1.45),
          ),
        ),
        const SizedBox(height: 10),

        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.icon(
              onPressed: () => _showSetPin6Dialog(context, isChange: hasPin6),
              icon: const Icon(Icons.dialpad, size: 17),
              label: Text(hasPin6 ? 'Đổi Mã PIN 6 Số' : 'Đặt Mã PIN 6 Số (Nhập 2 lần)'),
            ),
            FilledButton.tonalIcon(
              onPressed: () => _showSetPasswordDialog(context, isChange: hasPassword),
              icon: const Icon(Icons.password, size: 17),
              label: Text(hasPassword ? 'Đổi Mật Khẩu' : 'Đặt Mật Khẩu (Nhập 2 lần)'),
            ),
          ],
        ),

        if (hasPassword || hasPin6) ...[
          const SizedBox(height: 6),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Bật Khóa Bảo Mật Ứng Dụng'),
            subtitle: const Text('Yêu cầu xác thực mỗi khi mở ứng dụng'),
            value: lockEnabled,
            onChanged: (v) async {
              await _channel.invokeMethod('setLockEnabled', {'enabled': v});
              await _refreshStatusOnly();
            },
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Tự động khóa lại khi chuyển sang ứng dụng khác'),
            subtitle: const Text(
              'Ngay khi bạn thoát ra màn hình chính, chuyển sang app khác hoặc tắt màn hình, ứng dụng sẽ tự động khóa lại ngay lập tức.',
            ),
            value: autoLockOnSwitch,
            onChanged: (v) async {
              await _channel.invokeMethod('setAutoLockOnSwitch', {'enabled': v});
              await _refreshStatusOnly();
            },
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Bật Sinh Trắc Học Vân Tay (Làm Mặc Định)'),
            subtitle: Text(
              bioAvailable
                  ? 'Sau khi bật, cảm biến Vân tay siêu âm S22 Ultra sẽ được dùng làm phương thức mở khóa MẶC ĐỊNH.'
                  : 'Thiết bị chưa đăng ký vân tay trong Cài đặt Android.',
            ),
            value: bioEnabled,
            onChanged: !bioAvailable
                ? null
                : (v) async {
                    if (v) {
                      // Xác thực vân tay 1 lần trước khi đặt làm mặc định
                      _isBiometricPromptRunning = true;
                      final ok = await _channel.invokeMethod('authenticateBiometric');
                      _lastUnlockTimestampMs = DateTime.now().millisecondsSinceEpoch;
                      _isBiometricPromptRunning = false;
                      if (ok == true) {
                        await _channel.invokeMethod('setBiometricEnabled', {'enabled': true});
                        await _refreshStatusOnly();
                      }
                    } else {
                      await _channel.invokeMethod('setBiometricEnabled', {'enabled': false});
                      await _refreshStatusOnly();
                    }
                  },
          ),
        ],

        const Divider(height: 26),

        // ==================== PHẦN 2: QUYỀN HỆ THỐNG & DỊCH VỤ NGẦM ====================
        const Text(
          'Cấu Hình Quyền Hệ Thống S22 Ultra',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 10),
        _buildStepItem(
          stepNum: '1',
          title: 'Cầu nối Thu âm 2 Chiều & Lá Chắn Ngân Hàng 0ms (S22U Call Bridge)',
          description: (_status['accessibilityEnabled'] == true)
              ? '✅ ĐANG HOẠT ĐỘNG: Tự động thu âm 2 chiều rõ nét (.m4a) và tự động TẮT trong 0ms ngay khi bạn chạm vào bất kỳ App Ngân hàng / VNeID nào.'
              : 'Đang TẮT: Bấm [Bật S22U Call Bridge] (trong mục Ứng dụng đã cài đặt) hoặc dán lệnh vào Brevent để tự động kích hoạt 100%.',
          isDone: (_status['accessibilityEnabled'] == true),
          actionLabel: (_status['accessibilityEnabled'] == true)
              ? 'Đã Bật Cầu Nối Thu Âm 2 Chiều'
              : 'Bật S22U Call Bridge Ngay',
          onTap: () async {
            await _channel.invokeMethod('openAccessibilitySettings');
            await _refreshStatusOnly();
          },
          secondaryLabel: 'Copy Lệnh Brevent',
          onSecondaryTap: () async {
            await Clipboard.setData(
              const ClipboardData(
                text:
                    'pm grant com.s22ultra.callrecorder android.permission.WRITE_SECURE_SETTINGS; appops set com.s22ultra.callrecorder ACCESS_RESTRICTED_SETTINGS allow; settings put secure enabled_accessibility_services com.s22ultra.callrecorder/com.s22ultra.callrecorder.OTTCallAccessibilityService; settings put secure accessibility_enabled 1',
              ),
            );
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                  content: Text('Đã sao chép lệnh kích hoạt tự động Brevent vào bộ nhớ tạm!'),
                ),
              );
            }
          },
        ),
        const Divider(),
        _buildStepItem(
          stepNum: '2',
          title: 'Quyền Neo Âm Thanh Cuộc Gọi (Hiển thị trên ứng dụng khác)',
          description:
              'Giúp Android 16 cấp quyền thu âm Microphone song song cùng lúc với Zalo, Viber, Telegram, WhatsApp mà không cần bật Trợ năng.',
          isDone: overlayGranted,
          actionLabel: overlayGranted ? 'Đã cấp quyền Neo Âm thanh' : 'Cấp quyền Hiển thị trên ứng dụng khác',
          onTap: () async {
            await _channel.invokeMethod('openOverlaySettings');
          },
        ),
        const Divider(),
        _buildStepItem(
          stepNum: '3',
          title: 'Nhận diện Tên Ứng dụng & Người gọi (Thông báo & Usage Access)',
          description:
              'Tự động đánh thức bộ ghi âm khi App đang tắt và đặt tên file theo đúng tên người gọi Zalo, Messenger, Telegram.',
          isDone: notifListenerGranted || usageStatsGranted,
          actionLabel: notifListenerGranted ? 'Đã bật Nhận diện Thông báo' : 'Bật Quyền Nhận Diện Cuộc Gọi OTT',
          onTap: () async {
            await _channel.invokeMethod('openAccessibilitySettings');
          },
          secondaryLabel: usageStatsGranted ? null : 'Cấp thêm Usage Access',
          onSecondaryTap: usageStatsGranted
              ? null
              : () async {
                  await _channel.invokeMethod('openUsageAccessSettings');
                },
        ),
        const Divider(),
        _buildStepItem(
          stepNum: '4',
          title: 'Ghi âm Cuộc gọi SIM thường (Trình Điện thoại gốc Samsung)',
          description:
              'Tự động đồng bộ bản ghi .m4a Lossless từ trình Điện thoại Samsung sang thư mục vĩnh viễn /Download/S22U_Call_Recordings/.',
          isDone: true,
          actionLabel: 'Mở Cài đặt Cuộc gọi Samsung',
          onTap: () async {
            await _channel.invokeMethod('openSamsungCallSettings');
          },
        ),
        const Divider(),
        _buildStepItem(
          stepNum: '5',
          title: 'Quyền Microphone & Lưu trữ Vĩnh viễn (Xóa App không mất)',
          description:
              'Toàn bộ bản ghi âm được lưu trực tiếp tại /Download/S22U_Call_Recordings/ để khi gỡ cài đặt hay cập nhật App không bao giờ bị mất.',
          isDone: micGranted && storageGranted,
          actionLabel: (micGranted && storageGranted) ? 'Đã cấp đủ quyền' : 'Cấp quyền ngay',
          onTap: () async {
            await _channel.invokeMethod('requestPermissions');
            await _channel.invokeMethod('openAllFilesAccess');
            await _refreshAll();
          },
        ),
      ],
    );
  }

  // ==================== HỘP THOẠI ĐẶT MÃ PIN 6 SỐ (BẮT BUỘC NHẬP 2 LẦN) ====================
  Future<void> _showSetPin6Dialog(BuildContext context, {required bool isChange}) async {
    final pin1Ctrl = TextEditingController();
    final pin2Ctrl = TextEditingController();
    String errText = '';
    bool obscure1 = true;
    bool obscure2 = true;

    await showDialog(
      context: context,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setDialogState) {
            return AlertDialog(
              title: Text(
                isChange ? 'Đổi Mã PIN 6 Số' : 'Đặt Mã PIN 6 Số Mới',
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
              ),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Vui lòng nhập Mã PIN gồm đúng 6 chữ số (phải nhập 2 lần trùng khớp nhau):',
                      style: TextStyle(fontSize: 12.5),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: pin1Ctrl,
                      keyboardType: TextInputType.number,
                      maxLength: 6,
                      obscureText: obscure1,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: InputDecoration(
                        labelText: 'Nhập Mã PIN 6 số (Lần 1)',
                        counterText: '',
                        prefixIcon: const Icon(Icons.dialpad),
                        suffixIcon: IconButton(
                          icon: Icon(obscure1 ? Icons.visibility : Icons.visibility_off),
                          onPressed: () => setDialogState(() => obscure1 = !obscure1),
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    TextField(
                      controller: pin2Ctrl,
                      keyboardType: TextInputType.number,
                      maxLength: 6,
                      obscureText: obscure2,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: InputDecoration(
                        labelText: 'Nhập lại Mã PIN 6 số (Lần 2)',
                        counterText: '',
                        prefixIcon: const Icon(Icons.check_circle_outline),
                        suffixIcon: IconButton(
                          icon: Icon(obscure2 ? Icons.visibility : Icons.visibility_off),
                          onPressed: () => setDialogState(() => obscure2 = !obscure2),
                        ),
                      ),
                    ),
                    if (errText.isNotEmpty) ...[
                      const SizedBox(height: 10),
                      Text(
                        errText,
                        style: const TextStyle(color: Colors.redAccent, fontSize: 12.5, fontWeight: FontWeight.w600),
                      ),
                    ],
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(ctx).pop(),
                  child: const Text('Hủy'),
                ),
                FilledButton(
                  onPressed: () async {
                    final p1 = pin1Ctrl.text.trim();
                    final p2 = pin2Ctrl.text.trim();
                    if (p1.length != 6 || !RegExp(r'^\d{6}$').hasMatch(p1)) {
                      setDialogState(() => errText = 'Mã PIN phải gồm đúng 6 chữ số (0-9)!');
                      return;
                    }
                    if (p1 != p2) {
                      setDialogState(() => errText = 'Hai lần nhập Mã PIN 6 số không khớp nhau!');
                      return;
                    }
                    await _channel.invokeMethod('setPin6', {'pin': p1});
                    await _refreshStatusOnly();
                    if (ctx.mounted) Navigator.of(ctx).pop();
                    if (mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Đã lưu Mã PIN 6 số và kích hoạt Khóa Bảo Mật!')),
                      );
                    }
                  },
                  child: const Text('Lưu Mã PIN 6 Số'),
                ),
              ],
            );
          },
        );
      },
    );
  }

  // ==================== HỘP THOẠI ĐẶT PASSWORD (BẮT BUỘC NHẬP 2 LẦN) ====================
  Future<void> _showSetPasswordDialog(BuildContext context, {required bool isChange}) async {
    final pwd1Ctrl = TextEditingController();
    final pwd2Ctrl = TextEditingController();
    String errText = '';
    bool obscure1 = true;
    bool obscure2 = true;

    await showDialog(
      context: context,
      builder: (ctx) {
        return StatefulBuilder(
          builder: (ctx, setDialogState) {
            return AlertDialog(
              title: Text(
                isChange ? 'Đổi Mật Khẩu Bảo Mật' : 'Đặt Mật Khẩu Bảo Mật Ban Đầu',
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
              ),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Vui lòng nhập mật khẩu bảo mật (bắt buộc nhập 2 lần trùng khớp nhau):',
                      style: TextStyle(fontSize: 12.5),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: pwd1Ctrl,
                      obscureText: obscure1,
                      decoration: InputDecoration(
                        labelText: 'Nhập mật khẩu mới (Lần 1)',
                        prefixIcon: const Icon(Icons.key),
                        suffixIcon: IconButton(
                          icon: Icon(obscure1 ? Icons.visibility : Icons.visibility_off),
                          onPressed: () => setDialogState(() => obscure1 = !obscure1),
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    TextField(
                      controller: pwd2Ctrl,
                      obscureText: obscure2,
                      decoration: InputDecoration(
                        labelText: 'Nhập lại mật khẩu (Lần 2)',
                        prefixIcon: const Icon(Icons.verified_user_outlined),
                        suffixIcon: IconButton(
                          icon: Icon(obscure2 ? Icons.visibility : Icons.visibility_off),
                          onPressed: () => setDialogState(() => obscure2 = !obscure2),
                        ),
                      ),
                    ),
                    if (errText.isNotEmpty) ...[
                      const SizedBox(height: 10),
                      Text(
                        errText,
                        style: const TextStyle(color: Colors.redAccent, fontSize: 12.5, fontWeight: FontWeight.w600),
                      ),
                    ],
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(ctx).pop(),
                  child: const Text('Hủy'),
                ),
                FilledButton(
                  onPressed: () async {
                    final p1 = pwd1Ctrl.text.trim();
                    final p2 = pwd2Ctrl.text.trim();
                    if (p1.length < 4) {
                      setDialogState(() => errText = 'Mật khẩu phải có ít nhất 4 ký tự!');
                      return;
                    }
                    if (p1 != p2) {
                      setDialogState(() => errText = 'Hai lần nhập Mật khẩu không khớp nhau!');
                      return;
                    }
                    await _channel.invokeMethod('setPassword', {'password': p1});
                    await _refreshStatusOnly();
                    if (ctx.mounted) Navigator.of(ctx).pop();
                    if (mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Đã lưu Mật khẩu và kích hoạt Khóa Bảo Mật!')),
                      );
                    }
                  },
                  child: const Text('Lưu Mật Khẩu'),
                ),
              ],
            );
          },
        );
      },
    );
  }

  Widget _buildStepItem({
    required String stepNum,
    required String title,
    required String description,
    required bool isDone,
    required String actionLabel,
    required VoidCallback onTap,
    String? secondaryLabel,
    VoidCallback? onSecondaryTap,
  }) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              CircleAvatar(
                radius: 12,
                backgroundColor: isDone ? Colors.green : cs.primary,
                child: Text(
                  stepNum,
                  style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13.8),
                ),
              ),
              Icon(
                isDone ? Icons.check_circle : Icons.warning_amber_rounded,
                color: isDone ? Colors.greenAccent : Colors.amber,
                size: 20,
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            description,
            style: TextStyle(fontSize: 12.5, color: cs.onSurfaceVariant, height: 1.4),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              Align(
                alignment: Alignment.centerLeft,
                child: FilledButton.tonal(
                  onPressed: onTap,
                  child: Text(actionLabel, style: const TextStyle(fontSize: 12.5)),
                ),
              ),
              if (secondaryLabel != null && onSecondaryTap != null)
                OutlinedButton.icon(
                  onPressed: onSecondaryTap,
                  icon: const Icon(Icons.power_settings_new, size: 15, color: Colors.orangeAccent),
                  label: Text(
                    secondaryLabel,
                    style: const TextStyle(fontSize: 12.0, color: Colors.orangeAccent),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
