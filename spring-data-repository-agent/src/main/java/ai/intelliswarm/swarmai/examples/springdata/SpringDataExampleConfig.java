package ai.intelliswarm.swarmai.examples.springdata;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA wiring for the spring-data-repository-agent example.
 *
 * <p>Opt-in by property: the base {@code application.yml} excludes
 * {@link HibernateJpaAutoConfiguration} and {@link JpaRepositoriesAutoConfiguration} for fast
 * startup (most examples don't need JPA). This config re-imports them ONLY when
 * {@code swarmai.examples.spring-data.enabled=true}, which the example's {@code run.sh} sets.
 *
 * <p>{@code @EnableJpaRepositories} is also needed because the examples app has
 * spring-data-redis on the classpath (for the conversation-memory example), triggering Spring
 * Data's strict mode where every repository must be claimed explicitly by its module.
 */
@Configuration
@ConditionalOnProperty(name = "swarmai.examples.spring-data.enabled", havingValue = "true")
@ImportAutoConfiguration({HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@EnableJpaRepositories(basePackageClasses = CustomerRepository.class)
@EntityScan(basePackageClasses = Customer.class)
public class SpringDataExampleConfig {
}
