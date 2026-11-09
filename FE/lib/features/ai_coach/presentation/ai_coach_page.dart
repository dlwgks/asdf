import 'dart:convert';

import 'package:flutter/material.dart';

import '../../../core/network/api_client.dart';
import '../../../core/network/api_contract.dart';

class AiCoachMessage {
  const AiCoachMessage({
    required this.messageId,
    required this.role,
    required this.content,
    required this.createdAt,
  });
  final int messageId;
  final String role;
  final String content;
  final String createdAt;

  factory AiCoachMessage.fromJson(Map<String, dynamic> j) => AiCoachMessage(
        messageId: j['message_id'] as int,
        role: j['role'] as String,
        content: j['content'] as String,
        createdAt: j['created_at'] as String,
      );
}

class AiCoachPage extends StatefulWidget {
  const AiCoachPage({super.key, required this.api});
  final ApiClient api;

  @override
  State<AiCoachPage> createState() => _AiCoachPageState();
}

class _AiCoachPageState extends State<AiCoachPage> {
  final List<AiCoachMessage> _messages = [];
  final TextEditingController _input = TextEditingController();
  final ScrollController _scroll = ScrollController();
  bool _loading = false;
  bool _sending = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadMessages();
  }

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _loadMessages() async {
    setState(() { _loading = true; _error = null; });
    try {
      final data = await widget.api.send('GET', ApiContract.aiCoachMessages);
      final list = (data!['messages'] as List)
          .map((e) => AiCoachMessage.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      setState(() { _messages.clear(); _messages.addAll(list); _loading = false; });
      _scrollToBottom();
    } on ApiRequestError catch (e) {
      setState(() { _error = e.failure.displayMessage; _loading = false; });
    }
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty || _sending) return;
    _input.clear();
    setState(() { _sending = true; });
    try {
      final data = await widget.api.send('POST', ApiContract.aiCoachMessages,
          body: {'content': text}, timeout: const Duration(seconds: 60));
      final userMsg = AiCoachMessage.fromJson(
          Map<String, dynamic>.from(data!['user_message'] as Map));
      final assistantMsg = AiCoachMessage.fromJson(
          Map<String, dynamic>.from(data['assistant_message'] as Map));
      setState(() {
        _messages.add(userMsg);
        _messages.add(assistantMsg);
        _sending = false;
      });
      _scrollToBottom();
    } on ApiRequestError catch (e) {
      setState(() { _sending = false; });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(e.failure.displayMessage)));
      }
    }
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeOut);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('AI 코치'),
        actions: [
          if (_messages.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.delete_outline),
              tooltip: '대화 지우기',
              onPressed: _clearChat,
            ),
        ],
      ),
      body: Column(
        children: [
          Expanded(child: _buildMessageList(colors)),
          _buildInput(colors),
        ],
      ),
    );
  }

  Widget _buildMessageList(ColorScheme colors) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(_error!, style: TextStyle(color: colors.error)),
            const SizedBox(height: 12),
            TextButton(onPressed: _loadMessages, child: const Text('다시 시도')),
          ],
        ),
      );
    }
    if (_messages.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.fitness_center, size: 48, color: colors.primary.withOpacity(0.5)),
              const SizedBox(height: 16),
              Text('AI 코치에게 무엇이든 물어보세요!',
                  style: TextStyle(fontSize: 16, color: colors.onSurface.withOpacity(0.6))),
              const SizedBox(height: 8),
              Text('체형, 운동, 건강에 대한 맞춤형 조언을 드려요.',
                  style: TextStyle(fontSize: 13, color: colors.onSurface.withOpacity(0.4)),
                  textAlign: TextAlign.center),
            ],
          ),
        ),
      );
    }
    return ListView.builder(
      controller: _scroll,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      itemCount: _messages.length + (_sending ? 1 : 0),
      itemBuilder: (context, i) {
        if (i == _messages.length) return _buildTypingIndicator(colors);
        return _buildBubble(_messages[i], colors);
      },
    );
  }

  Widget _buildBubble(AiCoachMessage msg, ColorScheme colors) {
    final isUser = msg.role == 'user';
    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.75),
        decoration: BoxDecoration(
          color: isUser ? colors.primary : colors.surfaceContainerHighest,
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(16),
            topRight: const Radius.circular(16),
            bottomLeft: Radius.circular(isUser ? 16 : 4),
            bottomRight: Radius.circular(isUser ? 4 : 16),
          ),
        ),
        child: Text(
          msg.content,
          style: TextStyle(
            color: isUser ? colors.onPrimary : colors.onSurface,
            fontSize: 14,
          ),
        ),
      ),
    );
  }

  Widget _buildTypingIndicator(ColorScheme colors) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: colors.surfaceContainerHighest,
          borderRadius: const BorderRadius.only(
            topLeft: Radius.circular(16),
            topRight: Radius.circular(16),
            bottomRight: Radius.circular(16),
            bottomLeft: Radius.circular(4),
          ),
        ),
        child: const SizedBox(
          width: 40,
          height: 14,
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              _Dot(delay: 0),
              _Dot(delay: 150),
              _Dot(delay: 300),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildInput(ColorScheme colors) {
    return Container(
      padding: EdgeInsets.only(
        left: 16, right: 8, top: 8,
        bottom: MediaQuery.of(context).viewInsets.bottom + 8,
      ),
      decoration: BoxDecoration(
        color: colors.surface,
        border: Border(top: BorderSide(color: colors.outlineVariant, width: 0.5)),
      ),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _input,
              maxLines: 4,
              minLines: 1,
              maxLength: 500,
              buildCounter: (_, {required currentLength, required isFocused, maxLength}) => null,
              textInputAction: TextInputAction.newline,
              decoration: InputDecoration(
                hintText: '궁금한 것을 물어보세요...',
                filled: true,
                fillColor: colors.surfaceContainerHighest,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(20),
                  borderSide: BorderSide.none,
                ),
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              ),
            ),
          ),
          const SizedBox(width: 8),
          IconButton.filled(
            onPressed: _sending ? null : _send,
            icon: _sending
                ? const SizedBox(width: 20, height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.send),
          ),
        ],
      ),
    );
  }

  Future<void> _clearChat() async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('대화 내용 삭제'),
        content: const Text('AI 코치와의 대화 내용을 모두 삭제할까요?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('취소')),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('삭제')),
        ],
      ),
    );
    if (confirm != true) return;
    try {
      await widget.api.send('DELETE', ApiContract.aiCoachMessages);
      setState(() => _messages.clear());
    } on ApiRequestError catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.failure.displayMessage)));
      }
    }
  }
}

class _Dot extends StatefulWidget {
  const _Dot({required this.delay});
  final int delay;
  @override
  State<_Dot> createState() => _DotState();
}

class _DotState extends State<_Dot> with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double> _anim;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
        vsync: this, duration: const Duration(milliseconds: 600))
      ..repeat(reverse: true);
    _anim = Tween<double>(begin: 0.3, end: 1.0).animate(
      CurvedAnimation(
        parent: _ctrl,
        curve: Interval(widget.delay / 600.0, 1.0, curve: Curves.easeInOut),
      ),
    );
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: _anim,
      child: Container(
        width: 7,
        height: 7,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: Theme.of(context).colorScheme.onSurface.withOpacity(0.6),
        ),
      ),
    );
  }
}
