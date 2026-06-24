import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const UniversalSkipperApp());
}

class UniversalSkipperApp extends StatelessWidget {
  const UniversalSkipperApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Universal Video Automator',
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
  // Communication channels to native Android hardware layers
  static const MethodChannel _settingsChannel = MethodChannel('com.universal.automator/settings');
  static const MethodChannel _configChannel = MethodChannel('com.universal.automator/config');

  bool _isSystemPluginActive = false;
  double _reopenTimeout = 2.0;

  @override
  void initState() {
    super.initState();
    _verifySystemStatus();
  }

  // Checks if the native background service is turned on in Android settings
  Future<void> _verifySystemStatus() async {
    try {
      final bool isActive = await _settingsChannel.invokeMethod('checkActiveStatus');
      setState(() {
        _isSystemPluginActive = isActive;
      });
    } on PlatformException catch (e) {
      debugPrint("Error reading service status: ${e.message}");
    }
  }

  // Diverts the user directly to the Android System Accessibility window
  Future<void> _navigateToSystemSettings() async {
    try {
      await _settingsChannel.invokeMethod('launchSettingsWindow');
    } on PlatformException catch (e) {
      debugPrint("Error opening settings: ${e.message}");
    }
  }

  // Pushes configurations (like delay timing) down to the active background thread
  Future<void> _syncConfiguration() async {
    try {
      await _configChannel.invokeMethod('syncParams', {
        'delayMs': (_reopenTimeout * 1000).toInt(),
      });
    } on PlatformException catch (e) {
      debugPrint("Error syncing parameters: ${e.message}");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Universal Ad Skipper'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            onPressed: _verifySystemStatus,
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        key: const Key('main_layout_padding'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status Banner Widget
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: _isSystemPluginActive ? Colors.green.shade900 : Colors.amber.shade900,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                children: [
                  Icon(
                    _isSystemPluginActive ? Icons.verified_user : Icons.warning_amber_rounded,
                    size: 28,
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _isSystemPluginActive ? 'Automator Running' : 'Permission Required',
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _isSystemPluginActive
                              ? 'Monitoring active media elements.'
                              : 'Android OS requires explicit permission to interact with other apps.',
                          style: const TextStyle(fontSize: 13, color: Colors.white),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),

            if (!_isSystemPluginActive)
              ElevatedButton.icon(
                onPressed: _navigateToSystemSettings,
                icon: const Icon(Icons.open_in_new_rounded),
                label: const Text('Open Accessibility Settings'),
                style: ElevatedButton.styleFrom(padding: const EdgeInsets.symmetric(vertical: 14)),
              ),

            const SizedBox(height: 16),
            const Divider(),
            const SizedBox(height: 16),

            const Text(
              'Reopen Delay Calibration',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text('Buffer Window', style: TextStyle(color: Colors.grey)),
                Text('${_reopenTimeout.toStringAsFixed(1)}s', style: const TextStyle(fontWeight: FontWeight.bold)),
              ],
            ),
            Slider(
              value: _reopenTimeout,
              min: 1.0,
              max: 4.0,
              divisions: 6,
              onChanged: (val) {
                setState(() => _reopenTimeout = val);
              },
              onChangeEnd: (val) => _syncConfiguration(),
            ),
            const Text(
              'Adjust this if your YouTube home feed takes a moment to load after closing a video.',
              style: TextStyle(fontSize: 12, color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }
}