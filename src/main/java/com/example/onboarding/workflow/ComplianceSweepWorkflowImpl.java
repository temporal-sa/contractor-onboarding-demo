package com.example.onboarding.workflow;

import com.example.onboarding.activity.ComplianceActivities;
import com.example.onboarding.model.Models.ExpiringCoverage;
import com.example.onboarding.model.Models.SweepRequest;
import com.example.onboarding.model.Models.SweepSummary;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;

public class ComplianceSweepWorkflowImpl implements ComplianceSweepWorkflow {

  private final ComplianceActivities act =
      Workflow.newActivityStub(
          ComplianceActivities.class,
          ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

  @Override
  public SweepSummary sweep(SweepRequest req) {
    List<ExpiringCoverage> expiring = act.findExpiringCoverage(req.windowDays);
    SweepSummary summary = new SweepSummary();
    summary.expiring = expiring.size();
    for (ExpiringCoverage c : expiring) {
      // Idempotent start: a renewal already open for this contractor, coverage, and expiry is skipped.
      boolean started = act.startRenewal(c, req.timescale);
      if (started) {
        summary.started++;
      } else {
        summary.alreadyOpen++;
      }
      summary.contractors.add(c.contractorName + " (" + c.coverageType + ")");
    }
    return summary;
  }
}
