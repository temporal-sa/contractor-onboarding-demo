package com.example.onboarding.activity;

import com.example.onboarding.model.Models.ExpiringCoverage;
import io.temporal.activity.ActivityInterface;
import java.util.List;

@ActivityInterface
public interface ComplianceActivities {

  List<ExpiringCoverage> findExpiringCoverage(int windowDays);

  /** Returns true if a renewal workflow was started, false if one is already open or finished. */
  boolean startRenewal(ExpiringCoverage coverage, long timescale);

  void sendRenewalReminder(String contractorId, String coverageType, int level);

  void updateCoverage(String contractorId, String coverageType, String newExpiry);

  void suspendContractor(String contractorId, String reason);
}
