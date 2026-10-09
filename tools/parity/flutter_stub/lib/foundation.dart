// Minimal stand-in for the parts of package:flutter/foundation.dart that OpenScan's live-scan logic imports, so the parity tool runs on plain
// Dart without the Flutter SDK. Behaviour follows Flutter's own classes where the ported code depends on it.
export 'dart:typed_data';

typedef VoidCallback = void Function();
typedef ValueChanged<T> = void Function(T value);

class _VisibleForTesting {
  const _VisibleForTesting();
}

const Object visibleForTesting = _VisibleForTesting();

abstract class Listenable {
  void addListener(VoidCallback listener);
  void removeListener(VoidCallback listener);
}

abstract class ValueListenable<T> extends Listenable {
  T get value;
}

class ChangeNotifier implements Listenable {
  final List<VoidCallback> _listeners = [];

  @override
  void addListener(VoidCallback listener) => _listeners.add(listener);

  @override
  void removeListener(VoidCallback listener) => _listeners.remove(listener);

  void notifyListeners() {
    // Iterate over a copy so a listener may add or remove listeners while being notified.
    for (final listener in List.of(_listeners)) {
      listener();
    }
  }

  void dispose() => _listeners.clear();
}

/// Like Flutter's ValueNotifier: notifies only when the new value is not == the old one. OpenScan's Quad does not override ==, so for quads this
/// means a different instance.
class ValueNotifier<T> extends ChangeNotifier implements ValueListenable<T> {
  ValueNotifier(this._value);

  T _value;

  @override
  T get value => _value;

  set value(T newValue) {
    if (_value == newValue) return;
    _value = newValue;
    notifyListeners();
  }
}
