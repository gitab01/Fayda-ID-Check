// App wiring: one controller, one API client, one attempt at a time.
//
// Two things arrive through the build, not through the UI:
//   * `--dart-define=FAYDA_BASE_URL` — the service *origin*; the client adds `/api/v1` itself.
//     The default is the Android emulator's host loopback, which is how a phone emulator reaches
//     a verification service on the laptop;
//   * `--dart-define=FAYDA_TOKEN` — the bearer token the identity issuer hands out. It is kept in
//     a process field only: never written to shared preferences, never logged. With no token the
//     first call returns 401 and the flow lands on the "session ended" page, which is the honest
//     outcome — the app does not pretend to be signed in.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

import 'api/verification_api.dart';
import 'state/verification_controller.dart';
import 'ui/flow_screen.dart';
import 'ui/theme.dart';

const String _baseUrl = String.fromEnvironment(
  'FAYDA_BASE_URL',
  defaultValue: 'http://10.0.2.2:8080',
);
const String _token = String.fromEnvironment('FAYDA_TOKEN');

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations(<DeviceOrientation>[
    DeviceOrientation.portraitUp,
  ]);
  runApp(FaydaIdCheckApp(api: _defaultApi()));
}

VerificationApi _defaultApi() => HttpVerificationApi(
      baseUrl: _baseUrl,
      client: http.Client(),
      accessToken: () async => _token.isEmpty ? null : _token,
    );

class FaydaIdCheckApp extends StatefulWidget {
  const FaydaIdCheckApp({super.key, this.api});

  /// Injected so a test can drive the whole app against a recording fake.
  final VerificationApi? api;

  @override
  State<FaydaIdCheckApp> createState() => _FaydaIdCheckAppState();
}

class _FaydaIdCheckAppState extends State<FaydaIdCheckApp> {
  late final VerificationController controller;

  @override
  void initState() {
    super.initState();
    controller = VerificationController(api: widget.api ?? _defaultApi());
  }

  @override
  void dispose() {
    // Destroys the per-attempt key and zeroes any frames still held.
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Fayda-ID Check',
        debugShowCheckedModeBanner: false,
        theme: InkTokens.theme(),
        home: FlowScreen(controller: controller),
      );
}
