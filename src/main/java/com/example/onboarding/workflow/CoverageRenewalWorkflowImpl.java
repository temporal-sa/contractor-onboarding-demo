package com.example.onboarding.workflow;

import com.example.onboarding.activity.ComplianceActivities;
import com.example.onboarding.model.Models.RenewalRequest;
import com.example.onboarding.model.Models.RenewalResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class CoverageRenewalWorkflowImpl implements CoverageRenewalWorkflow {

  private static final int MAX_ESCALATIONS = 2;
  private static final Duration SLA = Duration.ofDays(2);

  private final ComplianceActivities act =
      Workflow.newActivityStub(
          ComplianceActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

  private String newExpiry;
  private String status = "STARTING";

  @Override
  public RenewalResult renew(RenewalRequest r) {
    long timescale = Math.max(1, r.timescale);
    Duration wait = Duration.ofMillis(Math.max(1000L, SLA.toMillis() / timescale));

    act.sendRenewalReminder(r.contractorId, r.coverageType, 0);
    status = "REMINDER_SENT";

    for (int level = 0; level <= MAX_ESCALATIONS; level++) {
      boolean renewed = Workflow.await(wait, () -> newExpiry != null);
      if (renewed) {
        act.updateCoverage(r.contractorId, r.coverageType, newExpiry);
        status = "RENEWED";
        return new RenewalResult(r.contractorId, r.coverageType, "RENEWED");
      }
      if (level < MAX_ESCALATIONS) {
        status = "ESCALATED_LEVEL_" + (level + 1);
        act.sendRenewalReminder(r.contractorId, r.coverageType, level + 1);
      }
    }

    act.suspendContractor(r.contractorId, r.coverageType + " not renewed before SLA expired");
    status = "SUSPENDED";
    return new RenewalResult(r.contractorId, r.coverageType, "SUSPENDED");
  }

  @Override
  public void renewalSubmitted(String expiry) {
    this.newExpiry = expiry;
  }

  @Override
  public String getStatus() {
    return status;
  }
}
