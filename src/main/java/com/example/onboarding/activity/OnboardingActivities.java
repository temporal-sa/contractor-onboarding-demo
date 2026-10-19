package com.example.onboarding.activity;

import com.example.onboarding.model.Models.ActivityResult;
import com.example.onboarding.model.Models.OnboardingRequest;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface OnboardingActivities {

  ActivityResult validateApplication(OnboardingRequest req);

  /** Outcome PASS, FLAG (manual compliance review), or FAIL (hard reject). */
  ActivityResult runScreening(OnboardingRequest req);

  ActivityResult verifyLicense(OnboardingRequest req, int attempt);

  ActivityResult verifyInsurance(OnboardingRequest req, int attempt);

  /** Sets score. Scores of 70 or more trigger manual risk review in the workflow. */
  ActivityResult computeRiskScore(OnboardingRequest req);

  /** Heartbeats while it runs so a killed worker is detected quickly. */
  ActivityResult verifyBankAccount(OnboardingRequest req);

  ActivityResult createVendorRecord(OnboardingRequest req);

  ActivityResult generateContract(OnboardingRequest req);

  ActivityResult activateContractor(OnboardingRequest req);

  void assignTask(String onboardingId, String stage, String team, String assignee);

  void escalateTask(String onboardingId, String stage, int level, String assignee);

  void sendNotification(String onboardingId, String message);
}
