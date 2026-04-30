package ai.intelliswarm.swarmai.examples.gmaildashboard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The customer-support example also ships a {@code static/index.html} at the
 * infra layer, and Maven's last-resource-copied wins the classpath race for
 * {@code /index.html}. So the IntelliMail UI lives under {@code /intellimail/}
 * and we forward {@code /} → {@code /intellimail/} only when the IntelliMail
 * workflow is the one running.
 */
@Configuration
@ConditionalOnProperty(name = "swarmai.intellimail.enabled", havingValue = "true")
public class IntelliMailWebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/intellimail/index.html");
    }
}
