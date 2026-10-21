package com.example.onboarding.config;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import java.net.InetAddress;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Temporal Spring Boot starter builds the client, schedule client, and (in the worker role)
 * the workers from the spring.temporal properties in application.yml. This class only adds what
 * those properties cannot express.
 */
@Configuration
public class TemporalConfig {

  private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

  @Value("${app.role:api}")
  private String role;

  @Bean
  public TemporalOptionsCustomizer<WorkflowServiceStubsOptions.Builder> serviceStubsCustomizer(
      TemporalProperties properties) {
    // A misspelled variable is silently ignored, so say what is actually in use.
    log.info(
        "Temporal connection: address={} namespace={} auth={}",
        properties.getConnection().getTarget(),
        properties.getNamespace(),
        authMode(properties));
    // Keeps the UI responsive when the worker is down and queries cannot be answered.
    return builder -> builder.setRpcQueryTimeout(Duration.ofSeconds(3));
  }

  @Bean
  public TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer() {
    return builder -> builder.setIdentity(role + "@" + host() + "-" + ProcessHandle.current().pid());
  }

  private static String authMode(TemporalProperties properties) {
    String apiKey = properties.getConnection().getApiKey();
    if (apiKey != null && !apiKey.isEmpty()) {
      return "api-key";
    }
    return properties.getConnection().getMTLS() != null ? "mtls" : "none";
  }

  private static String host() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown-host";
    }
  }
}
