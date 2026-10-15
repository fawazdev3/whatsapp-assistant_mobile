import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _native = MethodChannel('assistant/native');

void main() => runApp(const App());

class App extends StatelessWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Assistant WhatsApp',
      theme: ThemeData(colorSchemeSeed: Colors.green, useMaterial3: true),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final _server = TextEditingController(text: 'http://127.0.0.1:8000');
  final _user = TextEditingController();
  final _pass = TextEditingController();

  Timer? _timer;
  bool _listenerEnabled = false;
  bool _loggedIn = false;
  String _loggedUser = '';
  List<String> _log = const [];
  bool _busy = false;
  bool _prefilled = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _server.dispose();
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final enabled = await _native.invokeMethod<bool>('isListenerEnabled') ?? false;
      final session =
          Map<String, dynamic>.from(await _native.invokeMethod<Map>('getSession') ?? {});
      final log = List<String>.from(await _native.invokeMethod<List>('getLog') ?? const []);
      if (!mounted) return;

      final loggedIn = session['loggedIn'] == true;
      final username = (session['username'] as String?) ?? '';

      if (!_prefilled) {
        _prefilled = true;
        final baseUrl = session['baseUrl'] as String?;
        if (baseUrl != null && baseUrl.isNotEmpty) _server.text = baseUrl;
        if (username.isNotEmpty) _user.text = username;
      }

      // On ne reconstruit l'écran que si quelque chose a changé : sinon la sélection de texte saute
      if (enabled == _listenerEnabled &&
          loggedIn == _loggedIn &&
          username == _loggedUser &&
          listEquals(log, _log)) {
        return;
      }
      setState(() {
        _listenerEnabled = enabled;
        _loggedIn = loggedIn;
        _loggedUser = username;
        _log = log;
      });
    } on PlatformException {
      // On réessaiera au prochain passage
    }
  }

  Future<void> _call(String method) async {
    try {
      await _native.invokeMethod(method);
    } on PlatformException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
    await _refresh();
  }

  Future<void> _login() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    String? error;
    try {
      error = await _native.invokeMethod<String>('login', {
        'baseUrl': _server.text.trim(),
        'username': _user.text.trim(),
        'password': _pass.text,
      });
    } on PlatformException catch (e) {
      error = e.message ?? 'Erreur inconnue.';
    }
    if (error == null) _pass.clear();
    if (mounted) {
      setState(() {
        _busy = false;
        _error = error;
      });
    }
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Assistant WhatsApp')),
      body: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          Card(
            child: ListTile(
              leading: Icon(
                _listenerEnabled ? Icons.check_circle : Icons.error,
                color: _listenerEnabled ? Colors.green : Colors.red,
              ),
              title: Text(
                _listenerEnabled
                    ? 'Accès aux notifications activé'
                    : 'Accès aux notifications désactivé',
              ),
              trailing: TextButton(
                onPressed: () => _call('openListenerSettings'),
                child: const Text('Réglages'),
              ),
            ),
          ),
          Card(
            child: ListTile(
              leading: const Icon(Icons.battery_charging_full),
              title: const Text('Batterie'),
              subtitle: const Text('Choisis « sans restriction » pour cette application'),
              trailing: TextButton(
                onPressed: () => _call('openBatterySettings'),
                child: const Text('Réglages'),
              ),
            ),
          ),
          Card(child: _loggedIn ? _connectedTile() : _loginForm()),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
              child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: Text('Journal', style: Theme.of(context).textTheme.titleMedium),
              ),
              TextButton(onPressed: () => _call('clearLog'), child: const Text('Effacer')),
            ],
          ),
          if (_log.isEmpty)
            const Padding(
              padding: EdgeInsets.all(12),
              child: Text('Aucun événement pour le moment.'),
            )
          else
            for (final line in _log)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 4),
                child: SelectableText(line),
              ),
        ],
      ),
    );
  }

  Widget _connectedTile() {
    return ListTile(
      leading: const Icon(Icons.cloud_done, color: Colors.green),
      title: const Text('Connecté au serveur'),
      subtitle: Text(_loggedUser),
      trailing: TextButton(
        onPressed: () => _call('logout'),
        child: const Text('Déconnexion'),
      ),
    );
  }

  Widget _loginForm() {
    return Padding(
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Connexion au serveur', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          TextField(
            controller: _server,
            keyboardType: TextInputType.url,
            decoration: const InputDecoration(labelText: 'Adresse du serveur'),
          ),
          TextField(
            controller: _user,
            autocorrect: false,
            decoration: const InputDecoration(labelText: 'Identifiant'),
          ),
          TextField(
            controller: _pass,
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Mot de passe'),
          ),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: _busy ? null : _login,
            child: Text(_busy ? 'Connexion...' : 'Se connecter'),
          ),
        ],
      ),
    );
  }
}