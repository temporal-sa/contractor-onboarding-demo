package com.example.onboarding.workflow;

import com.example.onboarding.model.Models.SweepRequest;
import com.example.onboarding.model.Models.SweepSummary;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Started by the contractor-compliance-sweep Schedule. */
@WorkflowInterface
public interface ComplianceSweepWorkflow {

  @WorkflowMethod
  SweepSummary sweep(SweepRequest request);
}
