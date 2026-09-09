package de.visterion.dracul.executor;

/**
 * The parsed form of {@code dracul.executor.max-signal-age-days}, read exactly once in
 * {@link ExecutorDefaults} and injected into every consumer — {@code ExecutorWebhookController}
 * (which folds it into {@link VetoConfig} for veto #3) and {@link PendingSignalSweeper}. Same
 * pattern and same argument as {@link MechanismBudget}: two consumers reading the key with their
 * own {@code @Value} default literal is exactly the divergence this record forbids.
 *
 * <p>Public because it is a parameter of the public {@code ExecutorWebhookController} constructor.
 */
public record SignalAgePolicy(int maxSignalAgeDays) {
}
