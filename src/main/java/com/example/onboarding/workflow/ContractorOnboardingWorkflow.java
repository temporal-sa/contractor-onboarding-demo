package com.example.onboarding.workflow;

import com.example.onboarding.model.Models.CorrectionSignal;
import com.example.onboarding.model.Models.DecisionSignal;
import com.example.onboarding.model.Models.OnboardingRequest;
import com.example.onboarding.model.Models.OnboardingResult;
import com.example.onboarding.model.Models.OnboardingState;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ContractorOnboardingWorkflow {

  @WorkflowMethod
  OnboardingResult onboard(OnboardingRequest request);

  /** Approve or reject a human review step (screening, risk, banking/AP). */
  @SignalMethod
  void submitDecision(DecisionSignal decision);

  /** Corrected license or insurance information from the contractor. */
  @SignalMethod
  void submitCorrection(CorrectionSignal correction);

  @SignalMethod
  void contractSigned(String signer);

  @SignalMethod
  void withdraw(String reason);

  @QueryMethod
  OnboardingState getState();
}
