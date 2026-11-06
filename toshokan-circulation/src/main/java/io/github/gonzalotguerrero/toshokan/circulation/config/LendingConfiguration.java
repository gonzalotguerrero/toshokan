package io.github.gonzalotguerrero.toshokan.circulation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LendingProperties.class)
public class LendingConfiguration {
}
