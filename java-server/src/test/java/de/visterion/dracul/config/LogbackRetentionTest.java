package de.visterion.dracul.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the retention contract. The 14-day window is not observable on day one — a
 * production check would take a fortnight — so it is asserted here against the parsed
 * configuration instead of being believed.
 */
class LogbackRetentionTest {

    private LoggerContext configure() throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(
                getClass().getClassLoader().getResourceAsStream("logback-spring.xml"));
        return context;
    }

    @Test
    void keepsFourteenDaysOfDailyFiles() throws Exception {
        LoggerContext context = configure();

        var appender = (RollingFileAppender<?>) context.getLogger("ROOT").getAppender("FILE");
        assertThat(appender).as("appender FILE must exist").isNotNull();

        var policy = (TimeBasedRollingPolicy<?>) appender.getRollingPolicy();
        assertThat(policy.getMaxHistory()).isEqualTo(14);
        // Daily, gzipped: the %d without a pattern means one file per day.
        assertThat(policy.getFileNamePattern()).contains("%d{yyyy-MM-dd}").endsWith(".log.gz");
    }

    @Test
    void keepsTheConsoleAppenderSoDockerLogsStillWorks() throws Exception {
        LoggerContext context = configure();

        // The evening analysis reads `docker logs dracul` for five fixed markers
        // (fetch_dracul_log_flags). Replacing the console appender instead of adding
        // to it would blind it — this assertion is the guard against that.
        assertThat(context.getLogger("ROOT").getAppender("CONSOLE"))
                .as("appender CONSOLE must survive").isNotNull();
    }
}
