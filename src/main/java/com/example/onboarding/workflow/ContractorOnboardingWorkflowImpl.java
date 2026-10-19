package com.example.onboarding.workflow;

import com.example.onboarding.activity.OnboardingActivities;
import com.example.onboarding.model.Models.ActivityResult;
import com.example.onboarding.model.Models.CorrectionSignal;
import com.example.onboarding.model.Models.DecisionSignal;
import com.example.onboarding.model.Models.HistoryEntry;
import com.example.onboarding.model.Models.OnboardingRequest;
import com.example.onboarding.model.Models.OnboardingResult;
import com.example.onboarding.model.Models.OnboardingState;
import com.example.onboarding.model.Models.StageState;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ContractorOnboardingWorkflowImpl implements ContractorOnboardingWorkflow {

  private static final int MAX_ESCALATIONS = 2; // team lead, then manager
  private static final int HIGH_RISK_THRESHOLD = 70;

  private final RetryOptions retry =
      RetryOptions.newBuilder()
          .setInitialInterval(Duration.ofSeconds(1))
          .setBackoffCoefficient(1.5)
          .setMaximumInterval(Duration.ofSeconds(10))
          .setMaximumAttempts(6)
          .build();

  private final OnboardingActivities act =
      Workflow.newActivityStub(
          OnboardingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(retry)
              .build());

  /** Long-running activity that heartbeats, so a killed worker is detected within seconds. */
  private final OnboardingActivities longAct =
      Workflow.newActivityStub(
          OnboardingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofMinutes(2))
              .setHeartbeatTimeout(Duration.ofSeconds(6))
              .setRetryOptions(retry)
              .build());

  private final OnboardingState state = new OnboardingState();
  private final Map<String, DecisionSignal> decisions = new HashMap<>();
  private final Map<String, CorrectionSignal> corrections = new HashMap<>();
  private boolean contractSigned;
  private boolean withdrawn;
  private String withdrawReason;
  private long timescale = 1;

  /** Internal control flow for terminal outcomes. Always caught in onboard(). */
  private static class Terminate extends RuntimeException {
    final String status;

    Terminate(String status, String reason) {
      super(reason);
      this.status = status;
    }
  }

  // ------------------------------------------------------------------ main flow

  @Override
  public OnboardingResult onboard(OnboardingRequest req) {
    init(req);
    try {
      return run(req);
    } catch (Terminate t) {
      return finish(req, t.status, t.getMessage());
    }
  }

  private OnboardingResult run(OnboardingRequest req) {
    // 1. Intake
    begin(Stage.INTAKE);
    noteWorker(act.validateApplication(req));
    done(Stage.INTAKE, "Application validated");

    // 2. Screening
    begin(Stage.SCREENING);
    ActivityResult screening = act.runScreening(req);
    noteWorker(screening);
    if ("FAIL".equals(screening.outcome)) {
      fail(Stage.SCREENING, screening.detail);
      throw new Terminate("REJECTED", "Screening failed: " + screening.detail);
    }
    if ("FLAG".equals(screening.outcome)) {
      log(Stage.SCREENING, "Flagged", screening.detail);
      humanReview(req, Stage.SCREENING, "Compliance review rejected the contractor");
    }
    done(Stage.SCREENING, "Screening cleared");

    // 3. License and insurance verification run in parallel
    begin(Stage.LICENSE);
    begin(Stage.INSURANCE);
    Promise<ActivityResult> license = Async.function(act::verifyLicense, req, 0);
    Promise<ActivityResult> insurance = Async.function(act::verifyInsurance, req, 0);
    Promise.allOf(license, insurance).get();
    noteWorker(license.get());
    noteWorker(insurance.get());
    // Both branches have finished. Record both outcomes before deciding anything.
    boolean licenseOk = resolveVerification(req, Stage.LICENSE, license.get());
    boolean insuranceOk = resolveVerification(req, Stage.INSURANCE, insurance.get());
    if (!licenseOk || !insuranceOk) {
      throw new Terminate(
          "REJECTED",
          "Verification failed: "
              + (licenseOk ? "" : "license ")
              + (insuranceOk ? "" : "insurance"));
    }

    // 4. Risk assessment
    begin(Stage.RISK);
    ActivityResult risk = act.computeRiskScore(req);
    noteWorker(risk);
    log(Stage.RISK, "Scored", "Risk score " + risk.score);
    if (risk.score >= HIGH_RISK_THRESHOLD) {
      humanReview(req, Stage.RISK, "Risk review rejected the contractor");
    }
    done(Stage.RISK, "Risk score " + risk.score);

    // 5. Banking and AP setup
    begin(Stage.BANKING);
    noteWorker(longAct.verifyBankAccount(req));
    humanReview(req, Stage.BANKING, "AP rejected the vendor setup");
    noteWorker(act.createVendorRecord(req));
    done(Stage.BANKING, "Vendor record created");

    // 6. Contracting
    begin(Stage.CONTRACTING);
    noteWorker(act.generateContract(req));
    StageState contracting = state.find(Stage.CONTRACTING.name());
    contracting.waitingFor = "SIGNATURE";
    assign(req, Stage.CONTRACTING);
    boolean signed = awaitWithSla(req, Stage.CONTRACTING, MAX_ESCALATIONS, () -> contractSigned);
    contracting.waitingFor = null;
    if (!signed) {
      fail(Stage.CONTRACTING, "Contract not signed before SLA expired");
      throw new Terminate("WITHDRAWN", "Contract not signed before SLA expired");
    }
    done(Stage.CONTRACTING, "Contract signed");

    // 7. Final onboarding
    begin(Stage.ONBOARDING);
    noteWorker(act.activateContractor(req));
    act.sendNotification(req.onboardingId, "Contractor " + req.contractorName + " is onboarded");
    done(Stage.ONBOARDING, "Contractor activated");
    return finish(req, "ONBOARDED", "All stages complete");
  }

  // ------------------------------------------------------------ human waiting

  /** Waits for an approve or reject decision, with SLA timer and escalation. */
  private void humanReview(OnboardingRequest req, Stage stage, String rejectMessage) {
    StageState s = state.find(stage.name());
    s.waitingFor = "DECISION";
    assign(req, stage);
    boolean got =
        awaitWithSla(req, stage, MAX_ESCALATIONS, () -> decisions.containsKey(stage.name()));
    s.waitingFor = null;
    if (!got) {
      fail(stage, "No decision before SLA expired");
      throw new Terminate("WITHDRAWN", stage + " review not actioned before SLA expired");
    }
    DecisionSignal d = decisions.remove(stage.name());
    log(stage, "Decision", d.decision + " by " + d.approver);
    if ("REJECT".equalsIgnoreCase(d.decision)) {
      fail(stage, rejectMessage);
      throw new Terminate("REJECTED", rejectMessage);
    }
  }

  /**
   * Handles a license or insurance result. On failure, asks the contractor for a correction
   * and waits one SLA window, then re-verifies. Returns true when the check finally passes.
   */
  private boolean resolveVerification(OnboardingRequest req, Stage stage, ActivityResult first) {
    if ("PASS".equals(first.outcome)) {
      done(stage, "Verified");
      return true;
    }
    log(stage, "Failed", first.detail);
    StageState s = state.find(stage.name());
    s.waitingFor = "CORRECTION";
    assign(req, stage);
    // No escalation for correction windows: one SLA window, then give up.
    boolean got = awaitWithSla(req, stage, 0, () -> corrections.containsKey(stage.name()));
    s.waitingFor = null;
    if (!got) {
      fail(stage, "No correction received");
      return false;
    }
    corrections.remove(stage.name());
    ActivityResult retry =
        stage == Stage.LICENSE ? act.verifyLicense(req, 1) : act.verifyInsurance(req, 1);
    noteWorker(retry);
    if ("PASS".equals(retry.outcome)) {
      done(stage, "Verified after correction");
      return true;
    }
    fail(stage, "Still failing after correction: " + retry.detail);
    return false;
  }

  /**
   * Durable SLA wait. Returns true when the condition is met, false when the SLA and all
   * allowed escalations have expired. The timer is a Temporal timer, not a thread or a row in a
   * scheduler table, so it survives worker restarts.
   */
  private boolean awaitWithSla(
      OnboardingRequest req, Stage stage, int maxEscalations, Supplier<Boolean> condition) {
    StageState s = state.find(stage.name());
    int level = 0;
    while (true) {
      Duration wait = scaled(stage.sla);
      s.slaDeadline = Workflow.currentTimeMillis() + wait.toMillis();
      Workflow.await(wait, () -> condition.get() || withdrawn);
      if (withdrawn) {
        throw new Terminate("WITHDRAWN", withdrawReason);
      }
      if (condition.get()) {
        s.slaDeadline = 0;
        return true;
      }
      if (level >= maxEscalations) {
        s.slaDeadline = 0;
        return false;
      }
      level++;
      s.escalationLevel = level;
      s.assignee = stage.team + (level == 1 ? " Lead" : " Manager");
      if (level >= MAX_ESCALATIONS) {
        state.atRisk = true;
      }
      act.escalateTask(req.onboardingId, stage.name(), level, s.assignee);
      log(stage, "Escalated", "Level " + level + " to " + s.assignee);
    }
  }

  // ------------------------------------------------------------------ signals

  @Override
  public void submitDecision(DecisionSignal decision) {
    decisions.put(decision.stage, decision);
  }

  @Override
  public void submitCorrection(CorrectionSignal correction) {
    corrections.put(correction.stage, correction);
  }

  @Override
  public void contractSigned(String signer) {
    contractSigned = true;
    log(Stage.CONTRACTING, "Signed", "Signed by " + signer);
  }

  @Override
  public void withdraw(String reason) {
    withdrawn = true;
    withdrawReason = reason == null || reason.isBlank() ? "Withdrawn by request" : reason;
  }

  @Override
  public OnboardingState getState() {
    return state;
  }

  // ------------------------------------------------------------------ helpers

  private void init(OnboardingRequest req) {
    timescale = Math.max(1, req.timescale);
    state.onboardingId = req.onboardingId;
    state.contractorName = req.contractorName;
    state.trade = req.trade;
    state.scenario = req.scenario;
    for (Stage st : Stage.values()) {
      state.stages.add(new StageState(st.name(), st.team));
    }
    log(Stage.INTAKE, "Submitted", "Scenario " + req.scenario);
  }

  private void begin(Stage stage) {
    if (withdrawn) {
      throw new Terminate("WITHDRAWN", withdrawReason);
    }
    StageState s = state.find(stage.name());
    s.status = "ACTIVE";
    state.currentStage = stage.name();
    log(stage, "Started", s.ownerTeam);
  }

  private void done(Stage stage, String outcome) {
    StageState s = state.find(stage.name());
    s.status = "COMPLETED";
    s.outcome = outcome;
    s.slaDeadline = 0;
    s.waitingFor = null;
    log(stage, "Completed", outcome);
  }

  private void fail(Stage stage, String outcome) {
    StageState s = state.find(stage.name());
    s.status = "FAILED";
    s.outcome = outcome;
    s.slaDeadline = 0;
    s.waitingFor = null;
    log(stage, "Failed", outcome);
  }

  private void assign(OnboardingRequest req, Stage stage) {
    StageState s = state.find(stage.name());
    act.assignTask(req.onboardingId, stage.name(), stage.team, s.assignee);
  }

  private OnboardingResult finish(OnboardingRequest req, String status, String reason) {
    state.status = status;
    state.currentStage = null;
    for (StageState s : state.stages) {
      s.slaDeadline = 0;
      s.waitingFor = null;
      if ("ACTIVE".equals(s.status)) {
        s.status = "FAILED";
        s.outcome = reason;
      }
    }
    state.history.add(
        new HistoryEntry(Workflow.currentTimeMillis(), "ONBOARDING", status, reason));
    return new OnboardingResult(req.onboardingId, status, reason);
  }

  private void noteWorker(ActivityResult r) {
    if (r != null && r.worker != null) {
      state.lastWorker = r.worker;
    }
  }

  private void log(Stage stage, String event, String detail) {
    state.history.add(new HistoryEntry(Workflow.currentTimeMillis(), stage.name(), event, detail));
  }

  private Duration scaled(Duration real) {
    long ms = Math.max(1000L, real.toMillis() / timescale);
    return Duration.ofMillis(ms);
  }
}
