package ai.intelliswarm.examples.demorecorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import ai.intelliswarm.examples.demorecorder.trace.TraceWriter;

/**
 * Activates only when {@code swarmai.demo.record=true}. Otherwise no beans are created
 * and the examples run unchanged.
 */
@AutoConfiguration
@EnableConfigurationProperties(DemoRecorderProperties.class)
@ConditionalOnProperty(prefix = "swarmai.demo", name = "record", havingValue = "true")
public class DemoRecorderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper demoRecorderObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        m.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return m;
    }

    @Bean
    public TraceWriter traceWriter(ObjectMapper mapper) {
        return new TraceWriter(mapper);
    }

    @Bean
    public TranscriptRecorder transcriptRecorder(DemoRecorderProperties props, TraceWriter writer) {
        return new TranscriptRecorder(props, writer);
    }
}
