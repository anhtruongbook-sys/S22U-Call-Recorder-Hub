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

class _RecorderHomeScreenState extends State<RecorderHomeScreen> {
  static const MethodChannel _channel = MethodChannel('com.s22ultra.callrecorder/native');

  Map<String, dynamic> _status = {};
  List<Map<String, dynamic>> _recordings = [];
  String _selectedFilter = 'ALL';
  String _manualAppTag = 'Zalo';
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    _refreshAll();
    _pollTimer = Timer.periodic(const Duration(seconds: 1), (_) => _refreshStatusOnly());
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<void> _refreshAll() async {
    await _refreshStatusOnly();
    await _refreshRecordings();
  }

  Future<void> _refreshStatusOnly() async {
    try {
      final res = await _channel.invokeMethod('getSystemStatus');
      if (res is Map && mounted) {
        setState(() {
          _status = Map<String, dynamic>.from(res);
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
    final newGain = remoteGainBoost ?? ((_status['remoteGainBoost'] as num?)?.toDouble() ?? 4.5);
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
      try {
        await _channel.invokeMethod('startManualRecording', {'appTag': _manualAppTag});
        await _refreshStatusOnly();
      } on PlatformException catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Cần cấp quyền Microphone: ${e.message}')),
          );
        }
      }
    }
  }

  List<Map<String, dynamic>> get _filteredRecordings {
    if (_selectedFilter == 'ALL') return _recordings;
    return _recordings.where((r) {
      final src = (r['source'] ?? '').toString().toUpperCase();
      final cat = (r['category'] ?? '').toString().toUpperCase();
      if (_selectedFilter == 'SIM') return cat == 'CELLULAR_SIM';
      return src.contains(_selectedFilter);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final bool accEnabled = _status['accessibilityEnabled'] == true;
    final bool micGranted = _status['micPermissionGranted'] == true;
    final bool storageGranted = _status['storagePermissionGranted'] == true;
    final bool isRecording = _status['isRecording'] == true;
    final int peakAmp = (_status['peakAmplitude'] as num?)?.toInt() ?? 0;
    final double gainBoost = (_status['remoteGainBoost'] as num?)?.toDouble() ?? 4.5;
    final bool autoOtt = _status['autoRecordOttEnabled'] ?? true;
    final bool autoVol = _status['autoBoostVolume'] ?? true;
    final String model = (_status['model'] ?? 'SM-S908E (S22 Ultra)').toString();
    final String androidVer = (_status['androidRelease'] ?? '14').toString();
    final List<dynamic> installedOtt = (_status['installedOttApps'] as List?) ?? [];

    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          elevation: 0,
          scrolledUnderElevation: 0,
          title: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'S22 Ultra Call Recorder Hub',
                style: TextStyle(fontSize: 16.5, fontWeight: FontWeight.w700),
              ),
              Text(
                '$model • Android $androidVer (Non-Root Knox 0x0)',
                style: TextStyle(
                  fontSize: 11.5,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
          actions: [
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
              Tab(icon: Icon(Icons.verified_user_outlined, size: 20), text: 'Thiết Lập S22U'),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            _buildRecordingsTab(
              accEnabled: accEnabled,
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
            _buildS22SetupTab(
              accEnabled: accEnabled,
              micGranted: micGranted,
              storageGranted: storageGranted,
            ),
          ],
        ),
      ),
    );
  }

  /// TAB 1: GIAO DIỆN PHẲNG (SINGLE-LEVEL CARD & FLAT DIVIDERS - RULE 14)
  /// Tuyệt đối không lồng khung 4 cạnh bên trong thẻ hoặc dòng danh sách.
  Widget _buildRecordingsTab({
    required bool accEnabled,
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
        // 1. Thanh điểm nhấn lề trái (Left Accent Bar - Không đóng hộp 4 cạnh) khi cần cấp quyền
        if (!accEnabled || !micGranted)
          Container(
            margin: const EdgeInsets.only(bottom: 14),
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
                  'Cần hoàn tất kích hoạt cho Samsung S22 Ultra',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13.5),
                ),
                const SizedBox(height: 4),
                Text(
                  !micGranted
                      ? 'Chưa cấp quyền Microphone & Bộ nhớ để thu và đọc file ghi âm.'
                      : 'Chưa bật Chế độ Trực chiến Tự động (Foreground Audio Guard) cho Zalo, Viber, Telegram, WhatsApp.',
                  style: const TextStyle(fontSize: 12.5),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  children: [
                    if (!micGranted)
                      FilledButton.tonalIcon(
                        onPressed: () async {
                          await _channel.invokeMethod('requestPermissions');
                          await _refreshStatusOnly();
                        },
                        icon: const Icon(Icons.mic, size: 17),
                        label: const Text('Cấp quyền Mic & Bộ nhớ'),
                      ),
                    if (!accEnabled)
                      FilledButton.icon(
                        onPressed: () async {
                          await _channel.invokeMethod('openAccessibilitySettings');
                          await _refreshStatusOnly();
                        },
                        icon: const Icon(Icons.shield_outlined, size: 17),
                        label: const Text('Bật Trực Chiến OTT (1-Chạm)'),
                      ),
                  ],
                ),
              ],
            ),
          ),

        // 2. DUY NHẤT 1 Thẻ Điều Khiển Đơn Cấp (Single-Level Card)
        // Mọi phần tử con bên trong đều phẳng hoàn toàn (không có OutlineInputBorder hay hộp viền 4 cạnh lồng bên trong)
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: cs.surfaceContainerLow,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: isRecording ? Colors.redAccent : cs.outlineVariant.withOpacity(0.6),
              width: 1,
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Row(
                      children: [
                        Icon(
                          isRecording ? Icons.fiber_manual_record : Icons.radar,
                          color: isRecording ? Colors.redAccent : Colors.tealAccent,
                          size: 19,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            isRecording
                                ? 'ĐANG GHI ÂM (${_status['currentAppTag']})'
                                : 'Tự động trực chiến cuộc gọi',
                            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Text(
                    'DSP x${((_status['remoteGainBoost'] as num?)?.toDouble() ?? 4.5).toStringAsFixed(1)}',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      color: cs.primary,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                'Ứng dụng OTT gần nhất: ${_status['lastDetectedApp'] ?? 'Chưa có'}',
                style: TextStyle(fontSize: 12.5, color: cs.onSurfaceVariant),
              ),
              if (isRecording) ...[
                const SizedBox(height: 10),
                LinearProgressIndicator(
                  value: (peakAmp / 32767.0).clamp(0.02, 1.0),
                  minHeight: 6,
                  color: Colors.redAccent,
                ),
                const SizedBox(height: 4),
                Text(
                  'Biên độ tín hiệu PCM: $peakAmp / 32767',
                  style: const TextStyle(fontSize: 11.5),
                ),
              ],
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 10),
                child: Divider(height: 1),
              ),
              // Thanh chọn ứng dụng ghi thủ công phẳng (Không dùng OutlineInputBorder 4 cạnh)
              Row(
                children: [
                  const Text(
                    'Ghi thủ công:',
                    style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w500),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: _manualAppTag,
                        isDense: true,
                        isExpanded: true,
                        items: const [
                          DropdownMenuItem(value: 'Zalo', child: Text('Zalo Call')),
                          DropdownMenuItem(value: 'Viber', child: Text('Viber Call')),
                          DropdownMenuItem(value: 'Telegram', child: Text('Telegram Call')),
                          DropdownMenuItem(value: 'WhatsApp', child: Text('WhatsApp Call')),
                          DropdownMenuItem(value: 'SIM_Manual', child: Text('Gọi Thường (Thủ công)')),
                        ],
                        onChanged: isRecording
                            ? null
                            : (val) {
                                if (val != null) setState(() => _manualAppTag = val);
                              },
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: isRecording ? Colors.red : null,
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                      visualDensity: VisualDensity.compact,
                    ),
                    onPressed: _toggleManualRecording,
                    icon: Icon(isRecording ? Icons.stop : Icons.fiber_manual_record, size: 17),
                    label: Text(isRecording ? 'Dừng' : 'Ghi Ngay'),
                  ),
                ],
              ),
            ],
          ),
        ),

        const SizedBox(height: 14),

        // 3. Thanh lọc nguồn cuộc gọi dạng phẳng (side: BorderSide.none - Không viền 4 cạnh)
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            children: [
              for (final f in [
                {'id': 'ALL', 'label': 'Tất cả (${_recordings.length})'},
                {'id': 'SIM', 'label': 'SIM (Samsung)'},
                {'id': 'ZALO', 'label': 'Zalo'},
                {'id': 'VIBER', 'label': 'Viber'},
                {'id': 'TELEGRAM', 'label': 'Telegram'},
                {'id': 'WHATSAPP', 'label': 'WhatsApp'},
              ])
                Padding(
                  padding: const EdgeInsets.only(right: 6),
                  child: ChoiceChip(
                    label: Text(f['label']!, style: const TextStyle(fontSize: 12.5)),
                    selected: _selectedFilter == f['id'],
                    side: BorderSide.none,
                    showCheckmark: false,
                    visualDensity: VisualDensity.compact,
                    onSelected: (_) => setState(() => _selectedFilter = f['id']!),
                  ),
                ),
            ],
          ),
        ),

        const SizedBox(height: 10),

        // 4. Danh sách bản ghi âm: Thiết kế phẳng phân cách bằng đường kẻ ngang (Border-bottom),
        // tuyệt đối không đóng hộp 4 cạnh cho từng dòng hay lồng thẻ bên trong thẻ.
        if (items.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 32, horizontal: 12),
            child: Text(
              'Chưa có bản ghi âm nào trong danh mục này.\nKhi bạn gọi SIM hoặc gọi Zalo, Viber, Telegram, WhatsApp, bản ghi sẽ tự động xuất hiện tại đây.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
            ),
          )
        else
          ...items.map((item) {
            final path = (item['path'] ?? '').toString();
            final name = (item['name'] ?? '').toString();
            final source = (item['source'] ?? 'OTT').toString();
            final dateStr = (item['dateFormatted'] ?? '').toString();
            final sizeKB = item['sizeKB'] ?? 0;
            final sha = (item['sha256Short'] ?? '').toString();
            final isPlaying = playingPath == path && path.isNotEmpty;
            final isSim = source.contains('SIM');

            return Container(
              padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 10),
              decoration: BoxDecoration(
                color: isPlaying ? cs.primaryContainer.withOpacity(0.18) : Colors.transparent,
                border: Border(
                  left: BorderSide(
                    color: isPlaying
                        ? cs.primary
                        : (isSim ? Colors.green : Colors.lightBlue),
                    width: 3,
                  ),
                  bottom: BorderSide(
                    color: cs.outlineVariant.withOpacity(0.45),
                    width: 0.8,
                  ),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        source.toUpperCase(),
                        style: TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w800,
                          color: isSim ? Colors.green : cs.primary,
                        ),
                      ),
                      Text(
                        '$dateStr  •  $sizeKB KB',
                        style: TextStyle(
                          fontSize: 11.5,
                          color: cs.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 5),
                  Text(
                    name,
                    style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13.5),
                  ),
                  const SizedBox(height: 4),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Expanded(
                        child: Text(
                          'SHA-256: $sha...',
                          style: TextStyle(
                            fontSize: 11,
                            fontFamily: 'monospace',
                            color: cs.onSurfaceVariant,
                          ),
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          TextButton.icon(
                            style: TextButton.styleFrom(
                              visualDensity: VisualDensity.compact,
                              padding: const EdgeInsets.symmetric(horizontal: 6),
                            ),
                            onPressed: () async {
                              if (isPlaying) {
                                await _channel.invokeMethod('stopPlayback');
                              } else {
                                await _channel.invokeMethod('playRecording', {'path': path});
                              }
                              await _refreshStatusOnly();
                            },
                            icon: Icon(
                              isPlaying ? Icons.stop_circle : Icons.play_circle_fill,
                              size: 18,
                            ),
                            label: Text(isPlaying ? 'Dừng' : 'Nghe'),
                          ),
                          IconButton(
                            visualDensity: VisualDensity.compact,
                            tooltip: 'Chia sẻ file (Zalo, Telegram, Drive...)',
                            icon: Icon(Icons.share_outlined, size: 19, color: cs.primary),
                            onPressed: () async {
                              await _channel.invokeMethod('shareRecording', {'path': path});
                            },
                          ),
                          IconButton(
                            visualDensity: VisualDensity.compact,
                            tooltip: 'Lưu bản sao vào thư mục Download',
                            icon: const Icon(Icons.download_outlined, size: 19),
                            onPressed: () async {
                              final saved = await _channel.invokeMethod('exportToDownloads', {'path': path});
                              if (mounted && saved != null && saved.toString().isNotEmpty) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  SnackBar(content: Text('Đã lưu vào: $saved')),
                                );
                              }
                            },
                          ),
                          IconButton(
                            visualDensity: VisualDensity.compact,
                            tooltip: 'Xóa bản ghi',
                            icon: const Icon(Icons.delete_outline, size: 19),
                            onPressed: () async {
                              await _channel.invokeMethod('deleteRecording', {'path': path});
                              await _refreshRecordings();
                            },
                          ),
                        ],
                      ),
                    ],
                  ),
                ],
              ),
            );
          }),
      ],
    );
  }

  /// TAB 2: CẤU HÌNH DSP (PHẲNG HÓA 100% - CHỈ DÙNG ĐƯỜNG KẺ PHÂN CÁCH NGANG)
  Widget _buildDspConfigTab({
    required bool autoOtt,
    required bool autoVol,
    required double gainBoost,
    required List<dynamic> installedOtt,
  }) {
    final cs = Theme.of(context).colorScheme;

    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      children: [
        const Text(
          'Tự Động Hóa Ghi Âm OTT (Zalo, Viber, Telegram, WhatsApp)',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 6),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text(
            'Tự động ghi âm khi phát hiện cuộc gọi OTT',
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          ),
          subtitle: const Text(
            'Tự động kích hoạt khi AudioManager chuyển sang MODE_IN_COMMUNICATION trên Zalo, Viber, Telegram, WhatsApp.',
            style: TextStyle(fontSize: 12.5),
          ),
          value: autoOtt,
          onChanged: (v) => _updateDspSettings(autoRecordOtt: v),
        ),
        const Divider(),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text(
            'Tự động nâng âm lượng loa thoại S22 Ultra lên 85%',
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          ),
          subtitle: const Text(
            'Giúp loa thoại kép phía trên của S22 Ultra truyền âm thanh người bên kia vào Mic rõ nhất mà không cần bật loa ngoài.',
            style: TextStyle(fontSize: 12.5),
          ),
          value: autoVol,
          onChanged: (v) => _updateDspSettings(autoBoostVolume: v),
        ),
        const Divider(),
        const SizedBox(height: 12),
        Text(
          'Khuếch đại DSP giọng người bên kia (Remote Gain): x${gainBoost.toStringAsFixed(1)}',
          style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
        ),
        const SizedBox(height: 4),
        Text(
          'Tự động nhân biên độ tín hiệu nhỏ từ loa thoại (90–4800 PCM) và nén mềm giọng bạn nói để 2 bên cân bằng nhau.',
          style: TextStyle(fontSize: 12.5, color: cs.onSurfaceVariant),
        ),
        Slider(
          value: gainBoost.clamp(1.0, 8.0),
          min: 1.0,
          max: 8.0,
          divisions: 14,
          label: 'x${gainBoost.toStringAsFixed(1)}',
          onChanged: (val) => _updateDspSettings(remoteGainBoost: val),
        ),
        const Divider(),
        const SizedBox(height: 12),
        const Text(
          'Ứng dụng OTT đã nhận diện trên chiếc S22 Ultra của bạn:',
          style: TextStyle(fontWeight: FontWeight.w600, fontSize: 13.5),
        ),
        const SizedBox(height: 8),
        if (installedOtt.isEmpty)
          Text(
            'Đang quét Zalo / Viber / Telegram / WhatsApp...',
            style: TextStyle(fontSize: 12.5, color: cs.onSurfaceVariant),
          )
        else
          Wrap(
            spacing: 12,
            runSpacing: 6,
            children: installedOtt
                .map(
                  (app) => Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.check_circle, size: 16, color: Colors.green),
                      const SizedBox(width: 4),
                      Text(
                        app.toString(),
                        style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                      ),
                    ],
                  ),
                )
                .toList(),
          ),
      ],
    );
  }

  /// TAB 3: THIẾT LẬP S22 ULTRA (PHẲNG HÓA 100% - DÙNG ĐƯỜNG KẺ NGANG & NÚT PHẲNG)
  Widget _buildS22SetupTab({
    required bool accEnabled,
    required bool micGranted,
    required bool storageGranted,
  }) {
    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      children: [
        const Text(
          '3 Bước Kích Hoạt Trọn Vẹn Trên Samsung S22 Ultra (Không Root)',
          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
        ),
        const SizedBox(height: 8),

        // Bước 1
        ListTile(
          contentPadding: const EdgeInsets.symmetric(vertical: 4),
          leading: Icon(
            micGranted ? Icons.check_circle : Icons.error_outline,
            color: micGranted ? Colors.green : Colors.orange,
          ),
          title: const Text(
            'Bước 1: Cấp quyền Microphone & Đọc bộ nhớ',
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          ),
          subtitle: const Text(
            'Cho phép thu âm cuộc gọi OTT và đọc thư mục /Recordings/Call/ của Samsung.',
            style: TextStyle(fontSize: 12.5),
          ),
          trailing: FilledButton.tonal(
            onPressed: () => _channel.invokeMethod('requestPermissions'),
            child: Text(micGranted ? 'Đã cấp' : 'Cấp quyền'),
          ),
        ),
        const Divider(),

        // Bước 2
        ListTile(
          contentPadding: const EdgeInsets.symmetric(vertical: 4),
          leading: Icon(
            accEnabled ? Icons.check_circle : Icons.error_outline,
            color: accEnabled ? Colors.green : Colors.orange,
          ),
          title: const Text(
            'Bước 2: Bật Dịch vụ Trợ năng (S22 Ultra OTT Engine)',
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          ),
          subtitle: const Text(
            'Vào Cài đặt -> Hỗ trợ tiếp cận -> Ứng dụng đã cài đặt -> Bật "S22 Ultra OTT Call Recorder Engine".',
            style: TextStyle(fontSize: 12.5),
          ),
          trailing: FilledButton.tonal(
            onPressed: () => _channel.invokeMethod('openAccessibilitySettings'),
            child: Text(accEnabled ? 'Đã bật' : 'Mở Cài đặt'),
          ),
        ),
        const Divider(),

        // Bước 3
        ListTile(
          contentPadding: const EdgeInsets.symmetric(vertical: 4),
          leading: Icon(
            storageGranted ? Icons.check_circle : Icons.phone_in_talk,
            color: storageGranted ? Colors.green : Colors.blue,
          ),
          title: const Text(
            'Bước 3: Bật Tự động ghi âm SIM gốc của Samsung One UI',
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          ),
          subtitle: const Text(
            'Mở Cài đặt Cuộc gọi của Samsung -> Ghi âm cuộc gọi -> Bật "Tự động ghi âm". App sẽ tự động đồng bộ mọi file .m4a vào chung danh sách.',
            style: TextStyle(fontSize: 12.5),
          ),
          trailing: FilledButton.tonal(
            onPressed: () => _channel.invokeMethod('openSamsungCallSettings'),
            child: const Text('Mở Cài đặt SIM'),
          ),
        ),
        const Divider(),

        // Quyền đọc file đầy đủ
        ListTile(
          contentPadding: const EdgeInsets.symmetric(vertical: 4),
          leading: const Icon(Icons.folder_open),
          title: const Text(
            'Quyền đọc toàn bộ thư mục /Recordings/Call/',
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
          ),
          subtitle: Text(
            'Đường dẫn Samsung: ${_status['samsungCallFolderPath'] ?? '/storage/emulated/0/Recordings/Call'}',
            style: const TextStyle(fontSize: 12),
          ),
          trailing: FilledButton.tonal(
            onPressed: () => _channel.invokeMethod('openAllFilesAccess'),
            child: const Text('Cấp quyền File'),
          ),
        ),
      ],
    );
  }
}
