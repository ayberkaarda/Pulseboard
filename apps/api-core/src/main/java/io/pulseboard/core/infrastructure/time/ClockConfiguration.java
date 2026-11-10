package io.pulseboard.core.infrastructure.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Application clock (UTC) and scheduling support. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
