// Which screen the attempt is on, and how far along it is.
//
// The stage comes from [VerificationState] — never from a guess about what the last tap was. That
// is what lets a blocked step (expired attempt, rate limit, inference down) land on a page that
// says what happened and offers the one action that can actually work.

import 'package:flutter/material.dart';

import '../api/api_exception.dart';
import '../api/api_models.dart';
import '../state/verification_controller.dart';
import 'consent_screen.dart';
import 'document_capture_screen.dart';
import 'liveness_screen.dart';
import 'result_screen.dart';
import 'theme.dart';

class FlowScreen extends StatelessWidget {
  const FlowScreen({required this.controller, super.key});

  final VerificationController controller;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (BuildContext context, _) {
        final VerificationState state = controller.state;
        return Column(
          children: <Widget>[
            if (_showRail(state)) StepRail(state: state),
            Expanded(child: _body(state)),
          ],
        );
      },
    );
  }

  /// The camera stages already say where they are on-screen, so the rail only appears on the
  /// pages that have room for it.
  bool _showRail(VerificationState state) =>
      state.stage != VerificationStage.awaitingDocument &&
      state.stage != VerificationStage.documentUploading &&
      state.stage != VerificationStage.awaitingLiveness &&
      state.stage != VerificationStage.livenessUploading;

  Widget _body(VerificationState state) {
    return switch (state.stage) {
      VerificationStage.idle || VerificationStage.starting => ConsentScreen(controller: controller),
      VerificationStage.awaitingDocument || VerificationStage.documentUploading =>
        DocumentCaptureScreen(controller: controller),
      VerificationStage.awaitingLiveness ||
      VerificationStage.livenessUploading ||
      VerificationStage.deciding =>
        LivenessScreen(controller: controller),
      VerificationStage.finished => ResultScreen(controller: controller),
      VerificationStage.blocked => BlockedScreen(controller: controller),
    };
  }
}

/// The stage rail: where the attempt is, in the service's own state names.
class StepRail extends StatelessWidget {
  const StepRail({required this.state, super.key});

  final VerificationState state;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    const List<AttemptStatus> stages = <AttemptStatus>[
      AttemptStatus.created,
      AttemptStatus.captured,
      AttemptStatus.inferring,
      AttemptStatus.decided,
    ];
    final Set<AttemptStatus> reached = state.reachedStages.toSet();
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: Row(
        children: <Widget>[
          for (final AttemptStatus stage in stages)
            Expanded(
              child: Row(
                children: <Widget>[
                  Container(
                    width: 10,
                    height: 10,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: reached.contains(stage) ? InkTokens.accent : InkTokens.line,
                    ),
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(_short(stage),
                        style: text.bodyMedium?.copyWith(
                            color: reached.contains(stage) ? InkTokens.text : InkTokens.muted)),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  String _short(AttemptStatus stage) => switch (stage) {
        AttemptStatus.created => 'Started',
        AttemptStatus.captured => 'Captured',
        AttemptStatus.inferring => 'Checking',
        AttemptStatus.decided => 'Decision',
        _ => stage.userLabel,
      };
}

/// The page a blocked attempt lands on: what stopped, and the single action that can continue.
class BlockedScreen extends StatelessWidget {
  const BlockedScreen({required this.controller, super.key});

  final VerificationController controller;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final VerificationState state = controller.state;
    final FlowOutcome? outcome = state.failure;

    if (outcome == null) {
      return PageScaffold(
        title: 'The attempt stopped',
        body: InkCard(child: Text('Start again to continue.', style: text.bodyMedium)),
        footer: FilledButton(onPressed: controller.reset, child: const Text('Start again')),
      );
    }

    final InkTone tone = switch (outcome.kind) {
      FlowOutcomeKind.serviceUnavailable ||
      FlowOutcomeKind.tooManyRequests ||
      FlowOutcomeKind.offline =>
        InkTone.attention,
      FlowOutcomeKind.technicalProblem ||
      FlowOutcomeKind.attemptLost ||
      FlowOutcomeKind.captureRejected ||
      FlowOutcomeKind.challengeMismatch ||
      FlowOutcomeKind.signedOut =>
        InkTone.rejected,
      FlowOutcomeKind.wrongPermissions || FlowOutcomeKind.alreadySubmitted => InkTone.neutral,
    };

    return PageScaffold(
      step: 'Stopped',
      title: outcome.title,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          InkCard(
            tone: tone,
            child: Text(outcome.body, style: text.bodyLarge),
          ),
          const SizedBox(height: 16),
          Text(
            outcome.canRetryAttempt
                ? 'This attempt can be continued after the action below.'
                : 'This attempt cannot be continued. Starting again creates a fresh one.',
            style: text.bodyMedium,
          ),
        ],
      ),
      footer: Column(
        children: <Widget>[
          FilledButton(onPressed: _primary(), child: Text(outcome.actionLabel)),
          const SizedBox(height: 10),
          OutlinedButton(onPressed: controller.reset, child: const Text('Start over')),
        ],
      ),
    );
  }

  /// The one action that fits the failure. `FAILED_RETRYABLE` is the only state the retry endpoint
  /// accepts, so anything else goes back through a new attempt instead of pretending to work.
  VoidCallback _primary() {
    final VerificationState state = controller.state;
    if (state.attemptId != null && state.failure?.kind == FlowOutcomeKind.serviceUnavailable) {
      return () => controller.retry();
    }
    if (state.failure?.kind == FlowOutcomeKind.signedOut) {
      return () => controller.refresh();
    }
    return controller.reset;
  }
}
