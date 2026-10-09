// Step 4: the decision, in the service's own words.
//
// `guidance` is shown verbatim: the explanation is written by the institution that made the
// decision, and rewriting it on the phone would put words in its mouth. The component scores are
// displayed because §4 makes them part of the record, but they are labelled as the service's
// numbers, not translated into a verdict of the app's own.

import 'package:flutter/material.dart';

import '../api/api_models.dart';
import '../state/verification_controller.dart';
import 'theme.dart';

class ResultScreen extends StatelessWidget {
  const ResultScreen({required this.controller, super.key});

  final VerificationController controller;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final VerificationState state = controller.state;
    final DecisionRecord? record = state.decision;

    if (record == null) {
      return PageScaffold(
        title: 'No decision yet',
        subtitle: 'The service has not returned a decision for this attempt.',
        body: InkCard(
          child: Text(state.attemptId == null
              ? 'Start a new attempt to continue.'
              : 'Attempt ${state.attemptId} is still open. Check its status or start again.',
              style: text.bodyMedium),
        ),
        footer: FilledButton(onPressed: controller.reset, child: const Text('Start a new attempt')),
      );
    }

    final InkTone tone = switch (record.decision) {
      Decision.pass => InkTone.accepted,
      Decision.review => InkTone.attention,
      Decision.fail => InkTone.rejected,
    };

    return PageScaffold(
      step: 'Attempt ${state.attemptNo}'
          '${state.attemptsRemaining > 0 ? ' · ${state.attemptsRemaining} left' : ''}',
      title: record.decision.plainLabel,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          InkCard(
            tone: tone,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(record.guidance.isEmpty ? 'The service returned no guidance text.' : record.guidance,
                    style: text.bodyLarge),
                if (record.reasonCode != null) ...<Widget>[
                  const SizedBox(height: 10),
                  Text('Reason code: ${record.reasonCode}', style: text.bodyMedium),
                ],
                if (record.thresholdVersion != null)
                  Text('Profile version: ${record.thresholdVersion}', style: text.bodyMedium),
              ],
            ),
          ),
          if (record.scores != null) ...<Widget>[
            const SizedBox(height: 22),
            Text('Component scores', style: text.titleMedium),
            const SizedBox(height: 4),
            Text('As returned by the service, 0 to 1.', style: text.bodyMedium),
            const SizedBox(height: 12),
            _ScoreBar(label: 'Liveness', value: record.scores!.liveness),
            _ScoreBar(label: 'Face match', value: record.scores!.match),
            _ScoreBar(label: 'Document', value: record.scores!.document),
            if (record.compositeScore != null) ...<Widget>[
              const SizedBox(height: 8),
              Row(
                children: <Widget>[
                  Text('Composite', style: text.titleMedium),
                  const Spacer(),
                  Text(record.compositeScore!.toStringAsFixed(3), style: text.titleMedium),
                ],
              ),
            ],
          ],
          const SizedBox(height: 22),
          Text(
            'Your photographs were discarded from this device once the attempt closed.',
            style: text.bodyMedium,
          ),
        ],
      ),
      footer: FilledButton(
        onPressed: controller.reset,
        child: Text(state.attemptsRemaining > 0 ? 'Start another attempt' : 'Done'),
      ),
    );
  }
}

class _ScoreBar extends StatelessWidget {
  const _ScoreBar({required this.label, required this.value});

  final String label;
  final double value;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final double clamped = value.clamp(0.0, 1.0);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Text(label, style: text.bodyLarge),
              const Spacer(),
              Text(clamped.toStringAsFixed(3), style: text.bodyLarge),
            ],
          ),
          const SizedBox(height: 6),
          ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: clamped,
              minHeight: 6,
              backgroundColor: InkTokens.line,
              valueColor: const AlwaysStoppedAnimation<Color>(InkTokens.accent),
            ),
          ),
        ],
      ),
    );
  }
}
