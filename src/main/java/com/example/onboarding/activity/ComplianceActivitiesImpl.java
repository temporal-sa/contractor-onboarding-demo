package com.example.onboarding.activity;

import com.example.onboarding.model.Models.ExpiringCoverage;
import com.example.onboarding.model.Models.Ids;
import com.example.onboarding.model.Models.RenewalRequest;
import com.example.onboarding.registry.ContractorRegistry;
import com.example.onboarding.workflow.CoverageRenewalWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ComplianceActivitiesImpl implements ComplianceActivities {

  private static final Logger log = LoggerFactory.getLogger(ComplianceActivitiesImpl.class);

  private final ContractorRegistry registry;
  private final WorkflowClient client;
  private final String taskQueue;

  public ComplianceActivitiesImpl(
      ContractorRegistry registry, WorkflowClient client, String taskQueue) {
    this.registry = registry;
    this.client = client;
    this.taskQueue = taskQueue;
  }

  @Override
  public List<ExpiringCoverage> findExpiringCoverage(int windowDays) {
    return registry.findExpiring(windowDays);
  }

  @Override
  public boolean startRenewal(ExpiringCoverage c, long timescale) {
    RenewalRequest req = new RenewalRequest();
    req.contractorId = c.contractorId;
    req.contractorName = c.contractorName;
    req.coverageType = c.coverageType;
    req.expiry = c.expiry;
    req.timescale = timescale;

    CoverageRenewalWorkflow wf =
        client.newWorkflowStub(
            CoverageRenewalWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setWorkflowId(Ids.renewal(c.contractorId, c.coverageType, c.expiry))
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    try {
      WorkflowClient.start(wf::renew, req);
      log.info("Started renewal for {} {}", c.contractorName, c.coverageType);
      return true;
    } catch (WorkflowExecutionAlreadyStarted e) {
      return false;
    }
  }

  @Override
  public void sendRenewalReminder(String contractorId, String coverageType, int level) {
    log.info("Renewal reminder (level {}) sent to {} for {}", level, contractorId, coverageType);
  }

  @Override
  public void updateCoverage(String contractorId, String coverageType, String newExpiry) {
    registry.updateCoverage(contractorId, coverageType, newExpiry);
  }

  @Override
  public void suspendContractor(String contractorId, String reason) {
    log.warn("Suspending contractor {}: {}", contractorId, reason);
    registry.suspend(contractorId);
  }
}
