package de.visterion.dracul.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resolves a webhook token from Spring properties, mirroring the exact property
 * names used by each legacy registrar.
 *
 * <ul>
 *   <li>Names starting with {@code strigoi-}: {@code dracul.strigoi.<suffix>.webhook-token}
 *       (e.g. {@code strigoi-echo} → {@code dracul.strigoi.echo.webhook-token})</li>
 *   <li>All other names: {@code dracul.<name>.webhook-token}
 *       (e.g. {@code daywalker} → {@code dracul.daywalker.webhook-token},
 *       {@code voievod} → {@code dracul.voievod.webhook-token})</li>
 * </ul>
 */
@Component
public class AgentWebhookTokenResolver implements TokenResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentWebhookTokenResolver.class);

    private final Environment environment;

    public AgentWebhookTokenResolver(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String resolve(String agentName) {
        String prop;
        if (agentName.startsWith("strigoi-")) {
            String suffix = agentName.substring("strigoi-".length());
            prop = "dracul.strigoi." + suffix + ".webhook-token";
        } else {
            prop = "dracul." + agentName + ".webhook-token";
        }
        String token = environment.getProperty(prop);
        if (token == null) {
            log.warn("no webhook token configured for agent '{}' (property {})", agentName, prop);
        }
        return token;
    }
}
