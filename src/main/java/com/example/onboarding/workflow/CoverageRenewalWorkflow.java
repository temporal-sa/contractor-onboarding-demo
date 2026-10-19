package com.example.onboarding.workflow;

import com.example.onboarding.model.Models.RenewalRequest;
import com.example.onboarding.model.Models.RenewalResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CoverageRenewalWorkflow {

  @WorkflowMethod
  RenewalResult renew(RenewalRequest request);

  /** New expiry date (ISO) once the contractor submits renewed coverage. */
  @SignalMethod
  void renewalSubmitted(String newExpiry);

  @QueryMethod
  String getStatus();
}
