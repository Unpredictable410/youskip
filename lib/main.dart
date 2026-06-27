import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:fl_chart/fl_chart.dart';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:file_saver/file_saver.dart';

void main() {
  runApp(const UniversalSkipperApp());
}

class UniversalSkipperApp extends StatelessWidget {
  const UniversalSkipperApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YouSkip Automator',
      theme: ThemeData(
        brightness: Brightness.dark,
        primarySwatch: Colors.deepPurple,
        useMaterial3: true,
      ),
      home: const MainControlScreen(),
    );
  }
}

class MainControlScreen extends StatefulWidget {
  const MainControlScreen({Key? key}) : super(key: key);

  @override
  State<MainControlScreen> createState() => _MainControlScreenState();
}

class _MainControlScreenState extends State<MainControlScreen> {
  static const MethodChannel _settingsChannel = MethodChannel('com.universal.automator/settings');

  bool _hasSystemPermission = false;
  bool _isSniperActive = false;

  int _totalAdsToday = 0;
  List<int> _hourlyAdCounts = List.filled(24, 0);
  List<String> _adHistory = [];
  List<Map<String, dynamic>> _displayLogs = [];
  String _timeUntilClear = "--:--";

  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    _checkPermissionsAndState();
    _loadAnalytics();

    // Auto-refreshes the UI every second to update the countdown timer and check for new ads
    _refreshTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      _loadAnalytics();
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  Future<void> _checkPermissionsAndState() async {
    try {
      final bool hasPermission = await _settingsChannel.invokeMethod('checkSystemPermission');
      final bool isActive = await _settingsChannel.invokeMethod('getSoftPauseState');
      setState(() {
        _hasSystemPermission = hasPermission;
        _isSniperActive = isActive;
      });
    } on PlatformException catch (e) {
      debugPrint("Error: ${e.message}");
    }
  }

  Future<void> _toggleSniper() async {
    try {
      await _settingsChannel.invokeMethod('toggleSoftPause');
      _checkPermissionsAndState();
    } on PlatformException catch (e) {
      debugPrint("Error toggling: ${e.message}");
    }
  }

  Future<void> _openSettings() async {
    try {
      await _settingsChannel.invokeMethod('launchSettingsWindow');
    } on PlatformException catch (e) {
      debugPrint("Error opening settings: ${e.message}");
    }
  }

  Future<void> _loadAnalytics() async {
    final prefs = await SharedPreferences.getInstance();

    // Force Flutter to read the live file Kotlin is writing to
    await prefs.reload();

    setState(() {
      _totalAdsToday = prefs.getInt('TotalAdsToday') ?? 0;

      // 1. Grab the raw history text from Android
      String rawHistory = prefs.getString('AdHistory') ?? '';

      // 2. Convert it into the list for the UI
      _adHistory = rawHistory.isEmpty ? [] : rawHistory.split('||').reversed.toList();

      List<Map<String, dynamic>> groupedLogs = [];
      for (int i = 0; i < _adHistory.length; i++) {
        List<String> current = _adHistory[i].split('|');
        if (current.length < 3) continue;

        // If we see a (2 of 2), check if the next item down is (1 of 2)
        if (current[1].contains("(2 of 2)") && i + 1 < _adHistory.length) {
          List<String> next = _adHistory[i + 1].split('|');
          if (next.length >= 3 && next[1].contains("(1 of 2)")) {
            // Success! Group them together
            groupedLogs.add({'isDouble': true, 'ad2': current, 'ad1': next});
            i++; // Skip the next item because we just absorbed it!
            continue;
          }
        }
        // Otherwise, it's a normal single ad
        groupedLogs.add({'isDouble': false, 'ad1': current});
      }
      _displayLogs = groupedLogs;

      // ---------------------------------------------------------
      // 3. CHART DATA LOGIC: Group ads by hour of the day
      // ---------------------------------------------------------
      List<int> newHourlyData = List.filled(24, 0);
      for (String receipt in _adHistory) {
        List<String> parts = receipt.split('|');
        if (parts.isNotEmpty && parts[0].isNotEmpty) {
          try {
            DateTime date = DateTime.fromMillisecondsSinceEpoch(int.parse(parts[0]));
            newHourlyData[date.hour]++;
          } catch (e) {
            // Failsafe: Ignores any broken/old test data so the app doesn't crash
            debugPrint("Skipped malformed graph data");
          }
        }
      }
      _hourlyAdCounts = newHourlyData;
      // ---------------------------------------------------------

      // 4. Calculate the 24-Hour Timer
      int startTimeMs = prefs.getInt('AnalyticsStartTime') ?? DateTime.now().millisecondsSinceEpoch;
      DateTime expirationTime = DateTime.fromMillisecondsSinceEpoch(startTimeMs).add(const Duration(hours: 24));
      Duration diff = expirationTime.difference(DateTime.now());

      if (diff.isNegative) {
        _timeUntilClear = "Clearing...";
      } else {
        String hours = diff.inHours.toString().padLeft(2, '0');
        String minutes = diff.inMinutes.remainder(60).toString().padLeft(2, '0');
        String seconds = diff.inSeconds.remainder(60).toString().padLeft(2, '0');
        _timeUntilClear = "$hours:$minutes:$seconds";
      }
    });
  }

  Future<void> _clearLogs() async {
    bool? confirm = await showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text("Clear Analytics"),
        content: const Text("Are you sure you want to permanently delete today's ad history?"),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text("CANCEL")),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text("CLEAR", style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );

    if (confirm == true) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove('AdHistory');
      await prefs.remove('TotalAdsToday');
      // Force UI refresh
      _loadAnalytics();
    }
  }

  Future<void> _exportLogs() async {
    // 1. Pop up a sleek menu to ask the user what they want to do
    String? choice = await showModalBottomSheet<String>(
        context: context,
        backgroundColor: Colors.grey.shade900,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(20))),
        builder: (BuildContext context) {
          return SafeArea(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Padding(
                  padding: EdgeInsets.all(16.0),
                  child: Text("Export Options", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white)),
                ),
                ListTile(
                  leading: const CircleAvatar(backgroundColor: Colors.deepPurple, child: Icon(Icons.save_alt, color: Colors.white)),
                  title: const Text('Save to Phone'),
                  subtitle: const Text('Download to your files'),
                  onTap: () => Navigator.pop(context, 'save'),
                ),
                ListTile(
                  leading: const CircleAvatar(backgroundColor: Colors.blue, child: Icon(Icons.share, color: Colors.white)),
                  title: const Text('Share File'),
                  subtitle: const Text('Send via WhatsApp, Email, etc.'),
                  onTap: () => Navigator.pop(context, 'share'),
                ),
                const SizedBox(height: 16),
              ],
            ),
          );
        }
    );

    // If they swipe the menu away without picking, cancel the operation
    if (choice == null) return;

    try {
      // 2. Generate the beautiful text log (exactly as before)
      String formattedLogs = "=== YouSkip Daily Analytics ===\n\n";
      formattedLogs += "Total Ads Processed: $_totalAdsToday\n\n";

      if (_adHistory.isEmpty) {
        formattedLogs += "No ads logged yet today.";
      } else {
        for (String receipt in _adHistory) {
          List<String> parts = receipt.split('|');
          if (parts.length >= 3) {
            DateTime date = DateTime.fromMillisecondsSinceEpoch(int.parse(parts[0]));
            int hour = date.hour;
            String amPm = hour >= 12 ? 'PM' : 'AM';
            hour = hour % 12 == 0 ? 12 : hour % 12;
            String timeString = "${hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')} $amPm";

            String type = parts[1];
            String duration = double.parse(parts[2]).toStringAsFixed(1);
            String advertiser = parts.length >= 4 ? parts[3] : "Unknown Advertiser";

            formattedLogs += "[$timeString] - $type - ${duration}s (Advertiser: $advertiser)\n";
          }
        }
      }

      Uint8List bytes = Uint8List.fromList(formattedLogs.codeUnits);
      DateTime now = DateTime.now();
      String baseFileName = "YouSkip_Logs_${now.month}_${now.day}_${now.year}";

      // 3. Execute the chosen action
      if (choice == 'save') {
        // Option A: Save directly to the phone's storage
        await FileSaver.instance.saveAs(
          name: baseFileName,
          bytes: bytes,
          ext: "txt",
          mimeType: MimeType.text,
        );
      } else if (choice == 'share') {
        // Option B: Create a temporary file behind the scenes and open the Share menu
        final tempDir = await getTemporaryDirectory();
        final file = File('${tempDir.path}/$baseFileName.txt');
        await file.writeAsBytes(bytes);

        await Share.shareXFiles(
            [XFile(file.path)],
            text: 'Here are my YouSkip stats for today!'
        );
      }

    } catch (e) {
      debugPrint("Export Error: $e");
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text("Failed to export: $e"), backgroundColor: Colors.red),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: CustomScrollView(
        slivers: [
          // ---------------------------------------------------------
          // 1. THE APP BAR (With Refresh Button Restored)
          // ---------------------------------------------------------
          SliverAppBar(
            pinned: true,
            backgroundColor: Theme.of(context).scaffoldBackgroundColor,
            title: const Text('YouSkip Dashboard'),
            actions: [
              IconButton(
                  icon: const Icon(Icons.refresh),
                  onPressed: () {
                    _checkPermissionsAndState();
                    _loadAnalytics();
                  },
                  tooltip: "Refresh System"
              ),
              IconButton(icon: const Icon(Icons.delete_outline), onPressed: _clearLogs, tooltip: "Clear Logs"),
              IconButton(icon: const Icon(Icons.download), onPressed: _exportLogs, tooltip: "Export Logs"),
            ],
          ),

          // ---------------------------------------------------------
          // 2. THE SCROLL-AWAY HEADER (Full-Width Toggle & Banner)
          // ---------------------------------------------------------
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.only(left: 16, right: 16, top: 8, bottom: 16),
              child: Column(
                children: [
                  // --- FULL WIDTH POWER TOGGLE ---
                  GestureDetector(
                    onTap: _hasSystemPermission ? _toggleSniper : null,
                    child: AnimatedContainer(
                      width: double.infinity, // MAKES THE BUTTON WIDE
                      duration: const Duration(milliseconds: 300),
                      padding: const EdgeInsets.symmetric(vertical: 24),
                      decoration: BoxDecoration(
                        color: !_hasSystemPermission
                            ? Colors.grey.shade800
                            : (_isSniperActive ? Colors.green.shade700 : Colors.red.shade800),
                        borderRadius: BorderRadius.circular(20),
                        boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 10, offset: Offset(0, 5))],
                      ),
                      child: Column(
                        children: [
                          Icon(_isSniperActive ? Icons.power_settings_new : Icons.power_off, size: 48, color: Colors.white),
                          const SizedBox(height: 8),
                          Text(
                            !_hasSystemPermission
                                ? "SYSTEM DISABLED"
                                : (_isSniperActive ? "SYSTEM ACTIVE" : "SYSTEM PAUSED"),
                            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold, letterSpacing: 2),
                          ),
                        ],
                      ),
                    ),
                  ),

                  const SizedBox(height: 16),

                  // --- PERMISSION CARD ---
                  if (!_hasSystemPermission)
                    Card(
                      color: Colors.amber.shade900,
                      margin: EdgeInsets.zero,
                      child: ListTile(
                        leading: const Icon(Icons.warning_amber_rounded, color: Colors.white, size: 32),
                        title: const Text("System Permission Required", style: TextStyle(fontWeight: FontWeight.bold)),
                        subtitle: const Text("Tap to open Accessibility Settings and enable YouSkip."),
                        onTap: _openSettings,
                      ),
                    ),
                ],
              ),
            ),
          ),

          // ---------------------------------------------------------
          // 3. THE STICKY STAT BOXES (Locks to top when scrolling)
          // ---------------------------------------------------------
          SliverPersistentHeader(
            pinned: true, // This makes it stick!
            delegate: _StickyStatDelegate(
              child: Row(
                children: [
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Colors.deepPurple.withOpacity(0.2),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: Colors.deepPurple, width: 1),
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Text("Skipped Ads", style: TextStyle(color: Colors.grey, fontSize: 14)),
                          const SizedBox(height: 4),
                          Text("$_totalAdsToday", style: const TextStyle(fontSize: 28, fontWeight: FontWeight.bold, color: Colors.white)),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Colors.blueGrey.withOpacity(0.2),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(color: Colors.blueGrey, width: 1),
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Text("Logs Clear In", style: TextStyle(color: Colors.grey, fontSize: 14)),
                          const SizedBox(height: 4),
                          Text(_timeUntilClear, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Colors.white)),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),

          // ---------------------------------------------------------
          // 4. THE GRAPH (Scrolls up under the stat boxes)
          // ---------------------------------------------------------
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.only(left: 16, right: 16, top: 16, bottom: 8),
              child: Container(
                height: 200,
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: Colors.grey.shade900,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(color: Colors.white10, width: 1),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text("Today's Activity", style: TextStyle(color: Colors.grey, fontSize: 14, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 16),
                    Expanded(
                      child: BarChart(
                        BarChartData(
                          alignment: BarChartAlignment.spaceAround,
                          maxY: (_hourlyAdCounts.isNotEmpty ? _hourlyAdCounts.reduce(math.max).toDouble() + 1 : 5.0).clamp(5.0, double.infinity),
                          barTouchData: BarTouchData(enabled: false),
                          titlesData: FlTitlesData(
                            show: true,
                            topTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                            rightTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                            leftTitles: const AxisTitles(sideTitles: SideTitles(showTitles: false)),
                            bottomTitles: AxisTitles(
                              sideTitles: SideTitles(
                                showTitles: true,
                                getTitlesWidget: (value, meta) {
                                  if (value % 4 != 0) return const SizedBox.shrink();
                                  int hour = value.toInt();
                                  String amPm = hour >= 12 ? 'PM' : 'AM';
                                  int displayHour = hour % 12 == 0 ? 12 : hour % 12;
                                  return Padding(
                                    padding: const EdgeInsets.only(top: 8.0),
                                    child: Text("$displayHour$amPm", style: const TextStyle(color: Colors.grey, fontSize: 10)),
                                  );
                                },
                              ),
                            ),
                          ),
                          gridData: const FlGridData(show: false),
                          borderData: FlBorderData(show: false),
                          barGroups: List.generate(24, (index) {
                            return BarChartGroupData(
                              x: index,
                              barRods: [
                                BarChartRodData(
                                  toY: _hourlyAdCounts[index].toDouble(),
                                  color: _hourlyAdCounts[index] > 0 ? Colors.deepPurpleAccent : Colors.transparent,
                                  width: 8,
                                  borderRadius: BorderRadius.circular(4),
                                  backDrawRodData: BackgroundBarChartRodData(
                                    show: true,
                                    toY: (_hourlyAdCounts.isNotEmpty ? _hourlyAdCounts.reduce(math.max).toDouble() + 1 : 5.0).clamp(5.0, double.infinity),
                                    color: Colors.white10,
                                  ),
                                ),
                              ],
                            );
                          }),
                        ),
                        swapAnimationDuration: const Duration(milliseconds: 500),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),

          // ---------------------------------------------------------
          // 5. THE LOGS LIST
          // ---------------------------------------------------------
          _displayLogs.isEmpty
              ? const SliverFillRemaining(
            child: Center(child: Text("Waiting for ads...", style: TextStyle(color: Colors.grey))),
          )
              : SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            sliver: SliverList(
              delegate: SliverChildBuilderDelegate(
                    (context, index) {
                  var item = _displayLogs[index];
                  bool isDouble = item['isDouble'];
                  List<String> ad1 = item['ad1'];

                  String formatTime(String ms) {
                    DateTime d = DateTime.fromMillisecondsSinceEpoch(int.parse(ms));
                    int h = d.hour;
                    String amPm = h >= 12 ? 'PM' : 'AM';
                    h = h % 12 == 0 ? 12 : h % 12;
                    return "${h.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')} $amPm";
                  }

                  if (!isDouble) {
                    String time = formatTime(ad1[0]);
                    String type = ad1[1];
                    String speed = double.parse(ad1[2]).toStringAsFixed(1);
                    String advertiser = ad1.length >= 4 ? ad1[3] : "Unknown Advertiser";

                    IconData icon = type.contains("Short") ? Icons.fast_forward : (type.contains("Unskippable") ? Icons.block : Icons.timer);
                    Color color = type.contains("Short") ? Colors.green : (type.contains("Unskippable") ? Colors.orange : Colors.blue);

                    return Card(
                      margin: const EdgeInsets.only(bottom: 8),
                      child: ListTile(
                        leading: CircleAvatar(backgroundColor: color.withOpacity(0.2), child: Icon(icon, color: color)),
                        title: Text(type, style: const TextStyle(fontWeight: FontWeight.bold)),
                        subtitle: Text("$time • $advertiser"),
                        trailing: Text("${speed}s", style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                      ),
                    );
                  } else {
                    List<String> ad2 = item['ad2'];
                    double time1 = double.parse(ad1[2]);
                    double time2 = double.parse(ad2[2]);
                    double totalTime = time1 + time2;
                    String bundleTime = formatTime(ad2[0]);

                    return Card(
                      margin: const EdgeInsets.only(bottom: 8),
                      color: Colors.deepPurple.withOpacity(0.1),
                      child: ExpansionTile(
                        leading: CircleAvatar(backgroundColor: Colors.deepPurple.withOpacity(0.2), child: const Icon(Icons.layers, color: Colors.deepPurpleAccent)),
                        title: const Text("Double Ad Bundle", style: TextStyle(fontWeight: FontWeight.bold)),
                        subtitle: Text("$bundleTime • Click to expand"),
                        trailing: Text("${totalTime.toStringAsFixed(1)}s", style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.deepPurpleAccent)),
                        children: [
                          const Divider(height: 1, color: Colors.white10),
                          ListTile(
                            dense: true,
                            title: Text(ad1[1]),
                            subtitle: Text("${formatTime(ad1[0])} • ${ad1.length >= 4 ? ad1[3] : 'Unknown'}"),
                            trailing: Text("${time1.toStringAsFixed(1)}s", style: const TextStyle(color: Colors.grey)),
                          ),
                          ListTile(
                            dense: true,
                            title: Text(ad2[1]),
                            subtitle: Text("${formatTime(ad2[0])} • ${ad2.length >= 4 ? ad2[3] : 'Unknown'}"),
                            trailing: Text("${time2.toStringAsFixed(1)}s", style: const TextStyle(color: Colors.grey)),
                          ),
                        ],
                      ),
                    );
                  }
                },
                childCount: _displayLogs.length,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
class _StickyStatDelegate extends SliverPersistentHeaderDelegate {
  final Widget child;

  _StickyStatDelegate({required this.child});

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) {
    // Adding a background color prevents the logs from showing through the boxes when scrolling
    return Container(
      color: Theme.of(context).scaffoldBackgroundColor,
      padding: const EdgeInsets.only(left: 16, right: 16, top: 8, bottom: 8),
      child: child,
    );
  }

  @override
  double get maxExtent => 110.0; // The exact height of the stat boxes

  @override
  double get minExtent => 110.0;

  @override
  bool shouldRebuild(covariant SliverPersistentHeaderDelegate oldDelegate) => true;
}