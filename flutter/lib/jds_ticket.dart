// Opening a Sheriff Remote Control link (rustdesk://jds-ticket/<ticket>) for RustDesk
// The one time ticket is swapped with the Sheriff for the device's RustDesk ID and password, then the
// connection starts. The password only ever exists inside RustDesk: it's never on a command line
// or handed over by another program (e.g. a launcher program)
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:get/get.dart';
import 'package:http/http.dart' as http;

import 'common.dart';

// The full Sheriff address tickets are redeemed at. Set when building, so it isn't in the code:
// flutter build ... --dart-define=JDS_REDEEM_URL=https://example.com/api/...
// (the Windows workflow passes it in from the JDS_REDEEM_URL Actions variable)
const String _redeemUrl = String.fromEnvironment('JDS_REDEEM_URL');
// the link's "host" part: rustdesk://jds-ticket/<ticket>
const String kJdsTicketAuthority = 'jds-ticket';

Future<void> jdsConnectWithTicket(Uri uri) async {
  final ticket = uri.pathSegments.isNotEmpty ? uri.pathSegments.first : '';
  if (ticket.isEmpty) {
    showToast('Remote Control link has no ticket');
    return;
  }

  // built without --dart-define=JDS_REDEEM_URL=...
  if (_redeemUrl.isEmpty) {
    showToast('Remote Control failed: this RustDesk build has no Sheriff address');
    return;
  }

  try {
    final response = await http
        .post(
          Uri.parse(_redeemUrl),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({'ticket': ticket}),
        )
        .timeout(const Duration(seconds: 15));

    final reply = jsonDecode(response.body) as Map<String, dynamic>;

    // 404 "Invalid or expired ticket", 500 "Could not decrypt...": show the Sheriff's own message
    if (response.statusCode != 200) {
      showToast('Remote Control failed: ${reply['error'] ?? 'HTTP ${response.statusCode}'}');
      return;
    }

    final id = reply['id']?.toString() ?? '';
    final password = reply['password']?.toString() ?? '';
    if (id.isEmpty || password.isEmpty) {
      showToast('Remote Control failed: the Sheriff sent no connection details');
      return;
    }

    final context = Get.context;
    if (context == null) return;

    // Launch RustDesk via own function (same as typing the ID and password in)
    connect(context, id, password: password);
  } catch (e) {
    // never log the reply itself, it contains the password
    debugPrint('JDS ticket redeem failed: $e');
    showToast('Remote Control failed: could not reach the Sheriff');
  }
}
