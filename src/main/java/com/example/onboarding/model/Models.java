package com.example.onboarding.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain data classes with public fields and no-arg constructors so the default
 * Temporal Jackson data converter can serialize them. Times are epoch millis
 * and dates are ISO strings to avoid needing Jackson java.time modules.
 */
public final class Models {
  private Models() {}

  public static final String[] SCENARIOS = {
    "HAPPY_PATH", "SLOW_APPROVER", "BAD_LICENSE", "LAPSED_INSURANCE",
    "HIGH_RISK", "FLAKY_VENDORS", "SLOW_BANKING", "SCREENING_FAIL"
  };

  // ---------------------------------------------------------------- onboarding

  public static class OnboardingRequest {
    public String onboardingId;
    public String contractorName;
    public String trade;
    public String scenario = "HAPPY_PATH";
    public long timescale = 1;

    public OnboardingRequest() {}
  }

  public static class StageState {
    public String name;
    public String ownerTeam;
    public String status = "PENDING"; // PENDING, ACTIVE, COMPLETED, FAILED
    public String assignee;
    public int escalationLevel;
    public long slaDeadline; // epoch millis, 0 when no SLA is running
    public String waitingFor; // null, DECISION, CORRECTION, SIGNATURE
    public String outcome;

    public StageState() {}

    public StageState(String name, String ownerTeam) {
      this.name = name;
      this.ownerTeam = ownerTeam;
      this.assignee = ownerTeam;
    }
  }

  public static class HistoryEntry {
    public long timestamp;
    public String stage;
    public String event;
    public String detail;

    public HistoryEntry() {}

    public HistoryEntry(long timestamp, String stage, String event, String detail) {
      this.timestamp = timestamp;
      this.stage = stage;
      this.event = event;
      this.detail = detail;
    }
  }

  public static class OnboardingState {
    public String onboardingId;
    public String contractorName;
    public String trade;
    public String scenario;
    public String status = "IN_PROGRESS"; // IN_PROGRESS, ONBOARDED, REJECTED, WITHDRAWN
    public String currentStage;
    public boolean atRisk;
    public String lastWorker;
    public boolean stale; // set by the API when the workflow could not be queried
    public List<StageState> stages = new ArrayList<>();
    public List<HistoryEntry> history = new ArrayList<>();

    public OnboardingState() {}

    public StageState find(String name) {
      for (StageState s : stages) {
        if (s.name.equals(name)) {
          return s;
        }
      }
      return null;
    }
  }

  public static class OnboardingResult {
    public String onboardingId;
    public String status;
    public String reason;

    public OnboardingResult() {}

    public OnboardingResult(String onboardingId, String status, String reason) {
      this.onboardingId = onboardingId;
      this.status = status;
      this.reason = reason;
    }
  }

  public static class DecisionSignal {
    public String stage;
    public String decision; // APPROVE or REJECT
    public String approver;
    public String comment;

    public DecisionSignal() {}
  }

  public static class CorrectionSignal {
    public String stage;
    public String payload;

    public CorrectionSignal() {}
  }

  /** Generic activity result. outcome values depend on the activity (PASS, FAIL, FLAG, HIGH, LOW). */
  public static class ActivityResult {
    public String outcome = "PASS";
    public String detail;
    public int score;
    public String worker;

    public ActivityResult() {}

    public ActivityResult(String outcome, String detail, String worker) {
      this.outcome = outcome;
      this.detail = detail;
      this.worker = worker;
    }
  }

  // ---------------------------------------------------------- compliance sweep

  public static class Contractor {
    public String id;
    public String name;
    public String trade;
    public String status = "ACTIVE"; // ACTIVE, SUSPENDED
    public String insuranceExpiry; // ISO date
    public String licenseExpiry; // ISO date
    public String insuranceLastRenewal; // outcome of the last finished renewal, e.g. RENEWED
    public String licenseLastRenewal;

    public Contractor() {}
  }

  public static class ExpiringCoverage {
    public String contractorId;
    public String contractorName;
    public String coverageType; // INSURANCE or LICENSE
    public String expiry; // ISO date

    public ExpiringCoverage() {}
  }

  public static class SweepRequest {
    public int windowDays = 30;
    public long timescale = 1;

    public SweepRequest() {}

    public SweepRequest(int windowDays, long timescale) {
      this.windowDays = windowDays;
      this.timescale = timescale;
    }
  }

  public static class SweepSummary {
    public int expiring;
    public int started;
    public int alreadyOpen;
    public List<String> contractors = new ArrayList<>();

    public SweepSummary() {}
  }

  public static class RenewalRequest {
    public String contractorId;
    public String contractorName;
    public String coverageType;
    public String expiry;
    public long timescale = 1;

    public RenewalRequest() {}
  }

  public static class RenewalResult {
    public String contractorId;
    public String coverageType;
    public String outcome; // RENEWED or SUSPENDED

    public RenewalResult() {}

    public RenewalResult(String contractorId, String coverageType, String outcome) {
      this.contractorId = contractorId;
      this.coverageType = coverageType;
      this.outcome = outcome;
    }
  }

  /** Deterministic workflow ids so repeated sweeps cannot start duplicate renewals. */
  public static final class Ids {
    private Ids() {}

    public static String renewal(String contractorId, String coverageType, String expiry) {
      return "renewal-" + contractorId + "-" + coverageType + "-" + expiry;
    }
  }
}
