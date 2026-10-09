// The app's whole visual system, in one file.
//
// Deliberately flat: white page, near-black text, one green accent, and the three signal colours
// the flow needs (accepted, needs attention, rejected). No gradients, no tinted page bands — the
// only places colour appears are components, so a rejected step reads as red without the page
// itself arguing about it.

import 'package:flutter/material.dart';

class InkTokens {
  const InkTokens._();

  static const Color page = Color(0xFFFFFFFF);
  static const Color raised = Color(0xFFFFFFFF);
  static const Color text = Color(0xFF14181B);
  static const Color muted = Color(0xFF5B6268);
  static const Color line = Color(0xFFE2E6E9);
  static const Color accent = Color(0xFF1B6B4A);
  static const Color warning = Color(0xFF8A5A00);
  static const Color danger = Color(0xFFA32020);

  static ThemeData theme() => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: accent,
          primary: accent,
          surface: page,
        ),
        scaffoldBackgroundColor: page,
        appBarTheme: const AppBarTheme(
          backgroundColor: page,
          surfaceTintColor: Colors.transparent,
          foregroundColor: text,
          elevation: 0,
        ),
        textTheme: const TextTheme(
          headlineSmall: TextStyle(fontSize: 22, height: 1.25, fontWeight: FontWeight.w600, color: text),
          titleLarge: TextStyle(fontSize: 19, fontWeight: FontWeight.w600, color: text),
          titleMedium: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: text),
          bodyLarge: TextStyle(fontSize: 16, height: 1.45, color: text),
          bodyMedium: TextStyle(fontSize: 14.5, height: 1.45, color: muted),
          labelLarge: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
        ),
        dividerTheme: const DividerThemeData(color: line, thickness: 1, space: 1),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            backgroundColor: accent,
            foregroundColor: Colors.white,
            minimumSize: const Size.fromHeight(52),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          ),
        ),
        outlinedButtonTheme: OutlinedButtonThemeData(
          style: OutlinedButton.styleFrom(
            foregroundColor: accent,
            side: const BorderSide(color: line),
            minimumSize: const Size.fromHeight(48),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
          ),
        ),
      );
}

/// The page shell every screen shares: a safe area, a title block, and one primary action pinned
/// to the bottom so a thumb can always reach it.
class PageScaffold extends StatelessWidget {
  const PageScaffold({
    required this.title,
    required this.body,
    this.subtitle,
    this.footer,
    this.step,
    super.key,
  });

  final String title;
  final String? subtitle;

  /// The scrollable page content, above the footer.
  final Widget body;
  final Widget? footer;

  /// Rendered above the title as `Step 2 of 4` style context, or a stage label.
  final String? step;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: <Widget>[
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    if (step != null) ...<Widget>[
                      Text(step!.toUpperCase(), style: text.bodyMedium?.copyWith(color: InkTokens.muted)),
                      const SizedBox(height: 6),
                    ],
                    Text(title, style: text.headlineSmall),
                    if (subtitle != null) ...<Widget>[
                      const SizedBox(height: 8),
                      Text(subtitle!, style: text.bodyMedium),
                    ],
                    const SizedBox(height: 20),
                    body,
                  ],
                ),
              ),
            ),
            if (footer != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
                child: footer!,
              ),
          ],
        ),
      ),
    );
  }
}

/// A bordered block on the white page. Component-level tint only, never page-level.
class InkCard extends StatelessWidget {
  const InkCard({required this.child, this.tone = InkTone.neutral, this.padding, super.key});

  final Widget child;
  final InkTone tone;
  final EdgeInsets? padding;

  @override
  Widget build(BuildContext context) {
    final Color edge = tone.borderColor;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: tone.fillColor,
        border: Border.all(color: edge),
        borderRadius: BorderRadius.circular(12),
      ),
      padding: padding ?? const EdgeInsets.all(16),
      child: child,
    );
  }
}

enum InkTone {
  neutral(InkTokens.line, InkTokens.page),
  accepted(Color(0xFFBFDCCD), Color(0xFFF4FAF6)),
  attention(Color(0xFFE7D2A8), Color(0xFFFFF8EC)),
  rejected(Color(0xFFE6BDBD), Color(0xFFFDF4F4));

  const InkTone(this.borderColor, this.fillColor);

  final Color borderColor;
  final Color fillColor;
}
