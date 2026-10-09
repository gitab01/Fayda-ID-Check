package id.fayda.verification.infra.config;

import id.fayda.verification.domain.state.AttemptStateMachine;
import id.fayda.verification.service.decision.DecisionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the two pieces that are deliberately Spring-free: the lifecycle table
 * ({@link AttemptStateMachine}) and the decision composite ({@link DecisionService}). Both are
 * plain classes so they can be unit tested without a context, and both are here only to put them
 * into the graph for injection.
 */
@Configuration
public class DomainConfig {

    @Bean
    AttemptStateMachine attemptStateMachine() {
        return new AttemptStateMachine();
    }

    @Bean
    DecisionService decisionService() {
        return new DecisionService();
    }
}
