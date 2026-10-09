// Step 1: consent, and the document choice.
//
// The page carries the promises the app can actually keep — no image is stored on the phone, the
// captures are encrypted with a key generated for this attempt only, and a human reviewer sees a
// decision record, not the raw frames. Anything it could not guarantee is not claimed here.

import 'package:flutter/material.dart';

import '../api/api_models.dart';
import '../state/verification_controller.dart';
import 'theme.dart';

class ConsentScreen extends StatefulWidget {
  const ConsentScreen({required this.controller, super.key});

  final VerificationController controller;

  @override
  State<ConsentScreen> createState() => _ConsentScreenState();
}

class _ConsentScreenState extends State<ConsentScreen> {
  DocumentType _documentType = DocumentType.nationalId;
  bool _consented = false;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return PageScaffold(
      step: 'Before you start',
      title: 'Verify your ID document',
      subtitle: 'The service compares the document you photograph with a selfie taken during a '
          'movement check, then returns a decision.',
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text('Which document are you presenting?', style: text.titleMedium),
          const SizedBox(height: 10),
          SegmentedButton<DocumentType>(
            segments: DocumentType.values
                .map((DocumentType t) => ButtonSegment<DocumentType>(value: t, label: Text(t.label)))
                .toList(growable: false),
            selected: <DocumentType>{_documentType},
            onSelectionChanged: (Set<DocumentType> selection) =>
                setState(() => _documentType = selection.first),
          ),
          const SizedBox(height: 22),
          InkCard(child: _WhatHappens(text: text)),
          const SizedBox(height: 16),
          InkCard(
            tone: InkTone.attention,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text('Where your captures go', style: text.titleMedium),
                const SizedBox(height: 8),
                Text(
                  'The photographs leave this phone only after being encrypted with a key that '
                  'exists for this attempt and is thrown away when it ends. Nothing is saved in '
                  'the app, and a rejected attempt discards its captures.',
                  style: text.bodyMedium,
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          CheckboxListTile(
            value: _consented,
            onChanged: (bool? value) => setState(() => _consented = value ?? false),
            controlAffinity: ListTileControlAffinity.leading,
            contentPadding: EdgeInsets.zero,
            title: const Text('I agree to these checks for this attempt'),
            subtitle: Text('You can stop at any point before submitting; nothing is sent until '
                'the captures reach the service.', style: text.bodyMedium),
          ),
        ],
      ),
      footer: FilledButton(
        onPressed: _consented && !widget.controller.state.busy
            // The contract's `deviceInfo` is free text kept for fraud context. Model and OS
            // version are enough; nothing here identifies the handset.
            ? () => widget.controller.start(
                  documentType: _documentType,
                  deviceInfo: 'Fayda-ID Check mobile',
                )
            : null,
        child: const Text('Start verification'),
      ),
    );
  }
}

class _WhatHappens extends StatelessWidget {
  const _WhatHappens({required this.text});

  final TextTheme text;

  @override
  Widget build(BuildContext context) {
    const List<(String, String)> steps = <(String, String)>[
      ('Photograph the document', 'Hold the card flat inside the outline until the four corners '
          'and the lighting pass the on-device checks.'),
      ('Perform the movement check', 'The service sends the order of movements this attempt '
          'expects; follow the prompts as they appear.'),
      ('Read the decision', 'A pass, a review by a person, or a fail with the reason and what to '
          'do next.'),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Text('What happens, in order', style: text.titleMedium),
        const SizedBox(height: 10),
        for (int i = 0; i < steps.length; i++)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Container(
                  width: 22,
                  height: 22,
                  alignment: Alignment.center,
                  decoration: const BoxDecoration(color: InkTokens.accent, shape: BoxShape.circle),
                  child: Text('${i + 1}',
                      style: text.bodyMedium
                          ?.copyWith(color: Colors.white, fontWeight: FontWeight.w600)),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(steps[i].$1, style: text.titleMedium),
                      const SizedBox(height: 2),
                      Text(steps[i].$2, style: text.bodyMedium),
                    ],
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}
