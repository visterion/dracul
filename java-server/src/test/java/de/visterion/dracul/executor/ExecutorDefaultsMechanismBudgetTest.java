package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code classes = ExecutorDefaults.class} also instantiates {@code executorAgentDefaults},
 *  ExecutorDefaults' other {@code @Bean}, which needs a real {@link ObjectMapper} to read the
 *  executor's JSON schema/tool-catalog resources — a mock cannot fake that parsing, so this
 *  slice supplies a real one rather than excluding the bean. */
@SpringBootTest(classes = {ExecutorDefaults.class,
        ExecutorDefaultsMechanismBudgetTest.ObjectMapperTestConfig.class})
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.executor.mechanism-budget-pct=X:0.5"})
class ExecutorDefaultsMechanismBudgetTest {

    @TestConfiguration
    static class ObjectMapperTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired MechanismBudget budget;

    @Test
    void beanIsParsedOnceFromTheConfiguredKey() {
        assertThat(budget.spec()).isEqualTo("X:0.5");
        assertThat(budget.shareFor("x")).contains(0.5);
    }
}
