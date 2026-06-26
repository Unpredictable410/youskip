import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

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
  bool _isSniperActive = true;

  int _totalAdsToday = 0;
  List<String> _adHistory = [];
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

    setState(() {
      _totalAdsToday = prefs.getInt('TotalAdsToday') ?? 0;

      String rawHistory = prefs.getString('AdHistory') ?? '';
      _adHistory = rawHistory.isEmpty ? [] : rawHistory.split('||').reversed.toList(); // Newest first

      // Calculate countdown timer
      int startTimeMs = prefs.getInt('AnalyticsStartTime') ?? DateTime.now().millisecondsSinceEpoch;
      DateTime expirationTime = DateTime.fromMillisecondsSinceEpoch(startTimeMs).add(const Duration(hours: 24));
      Duration diff = expirationTime.difference(DateTime.now());

      if (diff.isNegative) {
        _timeUntilClear = "Clearing shortly...";
      } else {
        String hours = diff.inHours.toString().padLeft(2, '0');
        String minutes = diff.inMinutes.remainder(60).toString().padLeft(2, '0');
        String seconds = diff.inSeconds.remainder(60).toString().padLeft(2, '0');
        _timeUntilClear = "$hours:$minutes:$seconds";
      }
    });
  }

  Future<void> _exportLogs() async {
    try {
      String formattedLogs = "=== YouSkip Daily Analytics ===\n\n";
      formattedLogs += "Total Ads Handled: $_totalAdsToday\n\n";

      if (_adHistory.isEmpty) {
        formattedLogs += "No ads logged yet today.";
      } else {
        for (String receipt in _adHistory) {
          List<String> parts = receipt.split('|');
          if (parts.length >= 3) {
            DateTime date = DateTime.fromMillisecondsSinceEpoch(int.parse(parts[0]));
            String timeString = "${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}";
            String type = parts[1];
            String duration = double.parse(parts[2]).toStringAsFixed(1);

            formattedLogs += "[$timeString] - $type - ${duration}s\n";
          }
        }
      }

      final directory = await getTemporaryDirectory();
      final file = File('${directory.path}/youskip_logs.txt');
      await file.writeAsString(formattedLogs);

      await Share.shareXFiles([XFile(file.path)], text: 'My YouSkip Analytics');
    } catch (e) {
      debugPrint("Export Error: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('YouSkip Dashboard'),
        actions: [
          IconButton(icon: const Icon(Icons.download), onPressed: _exportLogs, tooltip: "Export Logs"),
          IconButton(icon: const Icon(Icons.refresh), onPressed: _checkPermissionsAndState),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // --- SYSTEM PERMISSION CARD ---
            if (!_hasSystemPermission)
              Card(
                color: Colors.amber.shade900,
                child: ListTile(
                  leading: const Icon(Icons.warning_amber_rounded, color: Colors.white, size: 32),
                  title: const Text("System Permission Required", style: TextStyle(fontWeight: FontWeight.bold)),
                  subtitle: const Text("Tap to open Accessibility Settings and enable YouSkip."),
                  onTap: _openSettings,
                ),
              ),

            const SizedBox(height: 16),

            // --- MAIN POWER TOGGLE ---
            GestureDetector(
              onTap: _hasSystemPermission ? _toggleSniper : null,
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 300),
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  color: !_hasSystemPermission
                      ? Colors.grey.shade800
                      : (_isSniperActive ? Colors.green.shade700 : Colors.red.shade800),
                  borderRadius: BorderRadius.circular(20),
                  boxShadow: [
                    BoxShadow(color: Colors.black26, blurRadius: 10, offset: const Offset(0, 5))
                  ],
                ),
                child: Column(
                  children: [
                    Icon(
                      _isSniperActive ? Icons.power_settings_new : Icons.power_off,
                      size: 64,
                      color: Colors.white,
                    ),
                    const SizedBox(height: 12),
                    Text(
                      _isSniperActive ? "SYSTEM ACTIVE" : "SYSTEM PAUSED",
                      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, letterSpacing: 2),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 32),

            // --- ANALYTICS HEADER ---
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text("Today's Analytics", style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                    Text("Total Processed: $_totalAdsToday", style: const TextStyle(color: Colors.grey)),
                  ],
                ),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    const Text("Next Reset In", style: TextStyle(fontSize: 12, color: Colors.grey)),
                    Text(_timeUntilClear, style: const TextStyle(fontSize: 16, fontFamily: 'monospace', fontWeight: FontWeight.bold)),
                  ],
                ),
              ],
            ),

            const Divider(height: 32),

            // --- AD HISTORY LIST ---
            Expanded(
              child: _adHistory.isEmpty
                  ? const Center(child: Text("Waiting for ads...", style: TextStyle(color: Colors.grey)))
                  : ListView.builder(
                itemCount: _adHistory.length,
                itemBuilder: (context, index) {
                  List<String> parts = _adHistory[index].split('|');
                  if (parts.length < 3) return const SizedBox();

                  DateTime date = DateTime.fromMillisecondsSinceEpoch(int.parse(parts[0]));
                  String time = "${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}";
                  String type = parts[1];
                  String speed = double.parse(parts[2]).toStringAsFixed(1);

                  // Change icon/color based on ad type
                  IconData icon = Icons.timer;
                  Color color = Colors.blue;
                  if (type.contains("Unskippable")) {
                    icon = Icons.block;
                    color = Colors.orange;
                  } else if (type.contains("Overlay")) {
                    icon = Icons.picture_in_picture;
                    color = Colors.purple;
                  }

                  return Card(
                    margin: const EdgeInsets.only(bottom: 8),
                    child: ListTile(
                      leading: CircleAvatar(backgroundColor: color.withOpacity(0.2), child: Icon(icon, color: color)),
                      title: Text(type, style: const TextStyle(fontWeight: FontWeight.bold)),
                      subtitle: Text(time),
                      trailing: Text("${speed}s", style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}