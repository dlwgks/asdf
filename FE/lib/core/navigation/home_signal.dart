import 'package:flutter/foundation.dart';

final ValueNotifier<int> homeTabRequests = ValueNotifier<int>(0);

void requestHomeTab() => homeTabRequests.value++;
