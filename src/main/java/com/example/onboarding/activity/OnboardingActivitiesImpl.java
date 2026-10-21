package com.example.onboarding.activity;

import com.example.onboarding.model.Models.ActivityResult;
import com.example.onboarding.model.Models.Contractor;
import com.example.onboarding.model.Models.OnboardingRequest;
import com.example.onboarding.registry.ContractorRegistry;
import io.temporal.activity.Activity;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Simulated integrations. Behavior is driven by the scenario carried in the request. */
@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class OnboardingActivitiesImpl implements OnboardingActivities {

  private static final Logger log = LoggerFactory.getLogger(OnboardingActivitiesImpl.class);

  private final ContractorRegistry registry;
  private final long delayMs;

  public OnboardingActivitiesImpl(
      ContractorRegistry registry, @Value("${demo.activity-delay-ms}") long delayMs) {
    this.registry = registry;
    this.delayMs = delayMs;
  }

  @Override
  public ActivityResult validateApplication(OnboardingRequest req) {
    pause();
    return ok("Application validated");
  }

  @Override
  public ActivityResult runScreening(OnboardingRequest req) {
    pause();
    flakeIfRequested(req, "screening vendor");
    switch (req.scenario) {
      case "SCREENING_FAIL":
        return new ActivityResult("FAIL", "Sanctions list match", worker());
      case "SLOW_APPROVER":
        return new ActivityResult("FLAG", "Adverse media hit needs analyst review", worker());
      default:
        return ok("Screening clear");
    }
  }

  @Override
  public ActivityResult verifyLicense(OnboardingRequest req, int attempt) {
    pause();
    flakeIfRequested(req, "state license lookup");
    if ("BAD_LICENSE".equals(req.scenario)) {
      return new ActivityResult("FAIL", "License not found or expired", worker());
    }
    return ok("License valid");
  }

  @Override
  public ActivityResult verifyInsurance(OnboardingRequest req, int attempt) {
    pause();
    flakeIfRequested(req, "insurance certificate service");
    if ("LAPSED_INSURANCE".equals(req.scenario) && attempt == 0) {
      return new ActivityResult("FAIL", "Certificate of insurance lapsed", worker());
    }
    return ok("Insurance valid");
  }

  @Override
  public ActivityResult computeRiskScore(OnboardingRequest req) {
    pause();
    ActivityResult r = ok("Risk model scored");
    r.score = "HIGH_RISK".equals(req.scenario) ? 85 : 20;
    r.outcome = r.score >= 70 ? "HIGH" : "LOW";
    return r;
  }

  @Override
  public ActivityResult verifyBankAccount(OnboardingRequest req) {
    if ("SLOW_BANKING".equals(req.scenario)) {
      // Long enough to kill the worker mid-activity. Heartbeats let the server notice within seconds.
      for (int i = 0; i < 20; i++) {
        Activity.getExecutionContext().heartbeat(i);
        sleep(1000);
      }
    } else {
      Activity.getExecutionContext().heartbeat(0);
      pause();
    }
    return ok("Bank account verified");
  }

  @Override
  public ActivityResult createVendorRecord(OnboardingRequest req) {
    pause();
    return ok("Vendor record created in AP system");
  }

  @Override
  public ActivityResult generateContract(OnboardingRequest req) {
    pause();
    return ok("Contract generated");
  }

  @Override
  public ActivityResult activateContractor(OnboardingRequest req) {
    pause();
    Contractor c = new Contractor();
    c.id = req.onboardingId;
    c.name = req.contractorName;
    c.trade = req.trade;
    c.insuranceExpiry = LocalDate.now().plusDays(365).toString();
    c.licenseExpiry = LocalDate.now().plusDays(365).toString();
    registry.add(c);
    return ok("Contractor activated");
  }

  @Override
  public void assignTask(String onboardingId, String stage, String team, String assignee) {
    log.info("[{}] task for stage {} assigned to {} ({})", onboardingId, stage, assignee, team);
  }

  @Override
  public void escalateTask(String onboardingId, String stage, int level, String assignee) {
    log.warn("[{}] stage {} escalated to level {}: now assigned to {}", onboardingId, stage, level, assignee);
  }

  @Override
  public void sendNotification(String onboardingId, String message) {
    log.info("[{}] notification: {}", onboardingId, message);
  }

  // ------------------------------------------------------------------ helpers

  private void flakeIfRequested(OnboardingRequest req, String system) {
    if ("FLAKY_VENDORS".equals(req.scenario)) {
      int attempt = Activity.getExecutionContext().getInfo().getAttempt();
      if (attempt < 3) {
        throw new IllegalStateException(
            "Simulated timeout calling " + system + " (attempt " + attempt + ")");
      }
    }
  }

  private ActivityResult ok(String detail) {
    return new ActivityResult("PASS", detail, worker());
  }

  private void pause() {
    sleep(delayMs);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted", e);
    }
  }

  static String worker() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      host = "unknown-host";
    }
    return host + "-" + ProcessHandle.current().pid();
  }
}
