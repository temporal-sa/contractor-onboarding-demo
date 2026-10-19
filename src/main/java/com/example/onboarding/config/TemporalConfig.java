package com.example.onboarding.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.envconfig.ClientConfigProfile;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connection setup from Temporal environment configuration: the TEMPORAL_* environment variables,
 * layered over a profile in temporal.toml if one exists (TEMPORAL_CONFIG_FILE, TEMPORAL_PROFILE).
 * Local dev server: no variables needed. Temporal Cloud: set address, namespace, and either
 * TEMPORAL_API_KEY or the mTLS client cert and key.
 * See https://docs.temporal.io/develop/environment-configuration
 */
@Configuration
public class TemporalConfig {

  private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

  @Value("${app.role:api}")
  private String role;

  @Bean
  public ClientConfigProfile clientConfigProfile() throws IOException {
    ClientConfigProfile profile = ClientConfigProfile.load();
    // An unrecognised or misspelled variable is ignored, so say what is actually in use.
    log.info(
        "Temporal connection: address={} namespace={} auth={}",
        orDefault(profile.getAddress(), "localhost:7233"),
        namespace(profile),
        profile.getApiKey() != null && !profile.getApiKey().isEmpty()
            ? "api-key"
            : profile.getTls() != null ? "tls" : "none");
    return profile;
  }

  @Bean(destroyMethod = "shutdown")
  public WorkflowServiceStubs serviceStubs(ClientConfigProfile profile) {
    return WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder(profile.toWorkflowServiceStubsOptions())
            // Keeps the UI responsive when the worker is down and queries cannot be answered.
            .setRpcQueryTimeout(Duration.ofSeconds(3))
            .build());
  }

  @Bean
  public WorkflowClient workflowClient(WorkflowServiceStubs stubs, ClientConfigProfile profile) {
    return WorkflowClient.newInstance(
        stubs,
        WorkflowClientOptions.newBuilder(profile.toWorkflowClientOptions())
            .setNamespace(namespace(profile))
            .setIdentity(role + "@" + host() + "-" + ProcessHandle.current().pid())
            .build());
  }

  @Bean
  public ScheduleClient scheduleClient(WorkflowServiceStubs stubs, ClientConfigProfile profile) {
    return ScheduleClient.newInstance(
        stubs, ScheduleClientOptions.newBuilder().setNamespace(namespace(profile)).build());
  }

  private static String namespace(ClientConfigProfile profile) {
    return orDefault(profile.getNamespace(), "default");
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isEmpty() ? fallback : value;
  }

  private static String host() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown-host";
    }
  }
}
