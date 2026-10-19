package com.example.onboarding.api;

import com.example.onboarding.model.Models;
import com.example.onboarding.model.Models.Contractor;
import com.example.onboarding.model.Models.CorrectionSignal;
import com.example.onboarding.model.Models.DecisionSignal;
import com.example.onboarding.model.Models.Ids;
import com.example.onboarding.model.Models.OnboardingRequest;
import com.example.onboarding.model.Models.OnboardingState;
import com.example.onboarding.registry.ContractorRegistry;
import com.example.onboarding.schedule.SweepScheduleService;
import com.example.onboarding.workflow.ContractorOnboardingWorkflow;
import com.example.onboarding.workflow.CoverageRenewalWorkflow;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.PollerInfo;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class ApiController {

  private static final String WORKFLOW_TYPE = "ContractorOnboardingWorkflow";

  private final WorkflowClient client;
  private final WorkflowServiceStubs service;
  private final SweepScheduleService schedule;
  private final ContractorRegistry registry;
  private final String namespace;
  private final String taskQueue;
  private final String uiUrl;
  private final long timescale;
  private final int windowDays;

  /** Last known state per onboarding, used when the worker is down and queries cannot be answered. */
  private final Map<String, OnboardingState> cache = new ConcurrentHashMap<>();

  public ApiController(
      WorkflowClient client,
      WorkflowServiceStubs service,
      SweepScheduleService schedule,
      ContractorRegistry registry,
      @Value("${temporal.task-queue}") String taskQueue,
      @Value("${temporal.ui-url}") String uiUrl,
      @Value("${demo.timescale}") long timescale,
      @Value("${demo.sweep-expiry-window-days}") int windowDays) {
    this.client = client;
    this.service = service;
    this.schedule = schedule;
    this.registry = registry;
    this.namespace = client.getOptions().getNamespace();
    this.taskQueue = taskQueue;
    this.uiUrl = uiUrl;
    this.timescale = Math.max(1, timescale);
    this.windowDays = windowDays;
  }

  // ------------------------------------------------------------------- config

  @GetMapping("/config")
  public Map<String, Object> config() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("namespace", namespace);
    m.put("taskQueue", taskQueue);
    m.put("uiUrl", uiUrl);
    m.put("timescale", timescale);
    m.put("windowDays", windowDays);
    m.put("scenarios", Models.SCENARIOS);
    m.put("scheduleId", SweepScheduleService.SCHEDULE_ID);
    m.put("recentPollers", recentPollers());
    return m;
  }

  /**
   * Pollers seen recently. Workers long-poll, so a healthy worker can look up to a minute old
   * and a killed worker takes about that long to disappear. The per-card stale flag is faster.
   */
  private int recentPollers() {
    try {
      DescribeTaskQueueResponse r =
          service
              .blockingStub()
              .withDeadlineAfter(2, TimeUnit.SECONDS)
              .describeTaskQueue(
                  DescribeTaskQueueRequest.newBuilder()
                      .setNamespace(namespace)
                      .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue))
                      .setTaskQueueType(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW)
                      .build());
      long now = System.currentTimeMillis();
      int count = 0;
      for (PollerInfo p : r.getPollersList()) {
        long last = p.getLastAccessTime().getSeconds() * 1000L + p.getLastAccessTime().getNanos() / 1_000_000L;
        if (now - last < 70_000L) {
          count++;
        }
      }
      return count;
    } catch (Exception e) {
      return -1;
    }
  }

  // -------------------------------------------------------------- onboardings

  @PostMapping("/onboardings")
  public Map<String, String> start(@RequestBody Map<String, String> body) {
    OnboardingRequest req = new OnboardingRequest();
    req.onboardingId = "ONB-" + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    req.contractorName = body.getOrDefault("contractorName", "Unnamed Contractor");
    req.trade = body.getOrDefault("trade", "General");
    req.scenario = body.getOrDefault("scenario", "HAPPY_PATH");
    req.timescale = timescale;

    ContractorOnboardingWorkflow wf =
        client.newWorkflowStub(
            ContractorOnboardingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(taskQueue)
                .setWorkflowId(req.onboardingId)
                .build());
    WorkflowClient.start(wf::onboard, req);
    return Map.of("onboardingId", req.onboardingId);
  }

  @GetMapping("/onboardings")
  public List<OnboardingState> list() {
    List<String> ids =
        client
            .listExecutions("WorkflowType='" + WORKFLOW_TYPE + "'")
            .limit(12)
            .map(m -> m.getExecution().getWorkflowId())
            .collect(Collectors.toList());

    List<CompletableFuture<OnboardingState>> futures = new ArrayList<>();
    for (String id : ids) {
      futures.add(CompletableFuture.supplyAsync(() -> fetchState(id)));
    }
    List<OnboardingState> out = new ArrayList<>();
    for (CompletableFuture<OnboardingState> f : futures) {
      OnboardingState s = f.join();
      if (s != null) {
        out.add(s);
      }
    }
    return out;
  }

  @GetMapping("/onboardings/{id}")
  public OnboardingState get(@PathVariable String id) {
    return fetchState(id);
  }

  private OnboardingState fetchState(String id) {
    OnboardingState cached = cache.get(id);
    if (cached != null && !"IN_PROGRESS".equals(cached.status)) {
      return cached; // terminal states do not change, no need to ask the worker again
    }
    try {
      OnboardingState s = onboarding(id).getState();
      s.stale = false;
      cache.put(id, s);
      return s;
    } catch (Exception e) {
      if (cached != null) {
        cached.stale = true;
      }
      return cached;
    }
  }

  @PostMapping("/onboardings/{id}/decision")
  public Map<String, String> decision(@PathVariable String id, @RequestBody Map<String, String> body) {
    DecisionSignal d = new DecisionSignal();
    d.stage = body.get("stage");
    d.decision = body.getOrDefault("decision", "APPROVE");
    d.approver = body.getOrDefault("approver", "demo-user");
    d.comment = body.get("comment");
    onboarding(id).submitDecision(d);
    return Map.of("status", "sent");
  }

  @PostMapping("/onboardings/{id}/correction")
  public Map<String, String> correction(@PathVariable String id, @RequestBody Map<String, String> body) {
    CorrectionSignal c = new CorrectionSignal();
    c.stage = body.get("stage");
    c.payload = body.getOrDefault("payload", "corrected document");
    onboarding(id).submitCorrection(c);
    return Map.of("status", "sent");
  }

  @PostMapping("/onboardings/{id}/sign")
  public Map<String, String> sign(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
    String signer = body == null ? "contractor" : body.getOrDefault("signer", "contractor");
    onboarding(id).contractSigned(signer);
    return Map.of("status", "sent");
  }

  @PostMapping("/onboardings/{id}/withdraw")
  public Map<String, String> withdraw(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
    String reason = body == null ? null : body.get("reason");
    onboarding(id).withdraw(reason);
    return Map.of("status", "sent");
  }

  private ContractorOnboardingWorkflow onboarding(String id) {
    return client.newWorkflowStub(ContractorOnboardingWorkflow.class, id);
  }

  // ----------------------------------------------------------------- schedule

  @GetMapping("/schedule")
  public Map<String, Object> scheduleState() {
    return schedule.describe();
  }

  @PostMapping("/schedule/trigger")
  public Map<String, String> trigger() {
    schedule.trigger();
    return Map.of("status", "triggered");
  }

  @PostMapping("/schedule/pause")
  public Map<String, String> pause() {
    schedule.pause();
    return Map.of("status", "paused");
  }

  @PostMapping("/schedule/resume")
  public Map<String, String> resume() {
    schedule.resume();
    return Map.of("status", "resumed");
  }

  @PostMapping("/schedule/reset")
  public Map<String, String> reset() {
    schedule.reset();
    return Map.of("status", "reset");
  }

  // ----------------------------------------------------------------- registry

  @GetMapping("/registry")
  public List<Map<String, Object>> registryList() {
    LocalDate cutoff = LocalDate.now().plusDays(windowDays);
    List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
    for (Contractor c : registry.list()) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.id);
                m.put("name", c.name);
                m.put("trade", c.trade);
                m.put("status", c.status);
                m.put("insuranceExpiry", c.insuranceExpiry);
                m.put("licenseExpiry", c.licenseExpiry);
                m.put("insuranceRenewal", renewalStatus(c, "INSURANCE", c.insuranceExpiry, c.insuranceLastRenewal, cutoff));
                m.put("licenseRenewal", renewalStatus(c, "LICENSE", c.licenseExpiry, c.licenseLastRenewal, cutoff));
                return m;
              }));
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (CompletableFuture<Map<String, Object>> f : futures) {
      out.add(f.join());
    }
    return out;
  }

  /**
   * Status of the renewal for the current expiry if one exists, otherwise the outcome of the last
   * finished renewal. A renewal moves the expiry out of the window, so its workflow is no longer
   * the one for the current expiry.
   */
  private String renewalStatus(
      Contractor c, String type, String expiry, String lastRenewal, LocalDate cutoff) {
    if (LocalDate.parse(expiry).isAfter(cutoff)) {
      return lastRenewal;
    }
    try {
      return client
          .newWorkflowStub(CoverageRenewalWorkflow.class, Ids.renewal(c.id, type, expiry))
          .getStatus();
    } catch (Exception e) {
      return lastRenewal; // no renewal workflow yet, or the worker is down
    }
  }

  @PostMapping("/registry/{contractorId}/renew")
  public Map<String, String> renew(@PathVariable String contractorId, @RequestBody Map<String, String> body) {
    String type = body.getOrDefault("coverageType", "INSURANCE");
    Contractor c = registry.find(contractorId);
    if (c == null) {
      throw new IllegalArgumentException("Unknown contractor " + contractorId);
    }
    String expiry = "INSURANCE".equals(type) ? c.insuranceExpiry : c.licenseExpiry;
    client
        .newWorkflowStub(CoverageRenewalWorkflow.class, Ids.renewal(c.id, type, expiry))
        .renewalSubmitted(LocalDate.now().plusDays(365).toString());
    return Map.of("status", "sent");
  }

  // ------------------------------------------------------------------- errors

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> onError(Exception e) {
    return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
  }
}
