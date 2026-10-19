package com.example.onboarding.schedule;

import com.example.onboarding.model.Models.SweepRequest;
import com.example.onboarding.model.Models.SweepSummary;
import com.example.onboarding.workflow.ComplianceSweepWorkflow;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionExecution;
import io.temporal.client.schedules.ScheduleActionExecutionStartWorkflow;
import io.temporal.client.schedules.ScheduleActionResult;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Owns the contractor-compliance-sweep Schedule: creates it at startup (idempotently) and
 * exposes describe, trigger, pause, resume, and reset for the front end.
 */
@Service
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class SweepScheduleService {

  public static final String SCHEDULE_ID = "contractor-compliance-sweep";
  private static final Logger log = LoggerFactory.getLogger(SweepScheduleService.class);

  private final ScheduleClient scheduleClient;
  private final WorkflowClient client;
  private final String taskQueue;
  private final Duration realInterval;
  private final long timescale;
  private final int windowDays;
  private final boolean startPaused;

  public SweepScheduleService(
      ScheduleClient scheduleClient,
      WorkflowClient client,
      @Value("${temporal.task-queue}") String taskQueue,
      @Value("${demo.sweep-interval}") String sweepInterval,
      @Value("${demo.timescale}") long timescale,
      @Value("${demo.sweep-expiry-window-days}") int windowDays,
      @Value("${demo.sweep-start-paused}") boolean startPaused) {
    this.scheduleClient = scheduleClient;
    this.client = client;
    this.taskQueue = taskQueue;
    this.realInterval = Duration.parse(sweepInterval);
    this.timescale = Math.max(1, timescale);
    this.windowDays = windowDays;
    this.startPaused = startPaused;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void ensureSchedule() {
    try {
      create();
      log.info("Created Schedule {} (every {}s)", SCHEDULE_ID, interval().getSeconds());
    } catch (Exception e) {
      log.info("Schedule {} not created (it may already exist): {}", SCHEDULE_ID, e.getMessage());
      if (startPaused) {
        // A Schedule left running by an earlier demo should also wait for its demo beat.
        try {
          handle().pause("Paused until the Schedule demo beat");
          log.info("Paused existing Schedule {}", SCHEDULE_ID);
        } catch (Exception pauseError) {
          log.warn("Could not pause Schedule {}: {}", SCHEDULE_ID, pauseError.getMessage());
        }
      }
    }
  }

  /** Real cadence divided by the demo timescale. 24h at timescale 4320 is about 20 seconds. */
  public Duration interval() {
    long seconds = Math.max(5, realInterval.getSeconds() / timescale);
    return Duration.ofSeconds(seconds);
  }

  private void create() {
    Schedule.Builder schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType(ComplianceSweepWorkflow.class)
                    .setArguments(new SweepRequest(windowDays, timescale))
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId("compliance-sweep")
                            .setTaskQueue(taskQueue)
                            .build())
                    .build())
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setIntervals(
                        Collections.singletonList(new ScheduleIntervalSpec(interval())))
                    .build())
            .setPolicy(
                SchedulePolicy.newBuilder()
                    // A slow sweep never stacks runs on top of each other.
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                    // Runs missed during downtime within this window are started when service resumes.
                    .setCatchupWindow(Duration.ofHours(1))
                    .build());
    if (startPaused) {
      schedule.setState(
          ScheduleState.newBuilder().setPaused(true).setNote("Paused until the Schedule demo beat").build());
    }
    scheduleClient.createSchedule(SCHEDULE_ID, schedule.build(), ScheduleOptions.newBuilder().build());
  }

  private ScheduleHandle handle() {
    return scheduleClient.getHandle(SCHEDULE_ID);
  }

  public void trigger() {
    handle().trigger();
  }

  public void pause() {
    handle().pause("Paused from the demo UI");
  }

  public void resume() {
    handle().unpause();
  }

  public void reset() {
    try {
      handle().delete();
    } catch (Exception e) {
      log.info("Nothing to delete: {}", e.getMessage());
    }
    create();
  }

  public Map<String, Object> describe() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", SCHEDULE_ID);
    out.put("intervalSeconds", interval().getSeconds());
    out.put("realInterval", realInterval.toString());
    out.put("overlapPolicy", "SKIP");
    try {
      ScheduleDescription d = handle().describe();
      out.put("exists", true);
      out.put("paused", d.getSchedule().getState().isPaused());
      out.put("numActions", d.getInfo().getNumActions());

      List<String> next = new ArrayList<>();
      for (Instant t : d.getInfo().getNextActionTimes()) {
        if (next.size() < 3) {
          next.add(t.toString());
        }
      }
      out.put("nextRuns", next);

      List<Map<String, Object>> recent = new ArrayList<>();
      List<ScheduleActionResult> actions = d.getInfo().getRecentActions();
      int from = Math.max(0, actions.size() - 5);
      for (int i = actions.size() - 1; i >= from; i--) {
        ScheduleActionResult r = actions.get(i);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scheduledAt", r.getScheduledAt().toString());
        ScheduleActionExecution action = r.getAction();
        if (action instanceof ScheduleActionExecutionStartWorkflow) {
          String workflowId = ((ScheduleActionExecutionStartWorkflow) action).getWorkflowId();
          row.put("workflowId", workflowId);
          if (i == actions.size() - 1) {
            row.put("summary", tryResult(workflowId));
          }
        }
        recent.add(row);
      }
      out.put("recentRuns", recent);
    } catch (Exception e) {
      out.put("exists", false);
      out.put("error", e.getMessage());
    }
    return out;
  }

  private SweepSummary tryResult(String workflowId) {
    try {
      return client
          .newUntypedWorkflowStub(workflowId)
          .getResult(2, TimeUnit.SECONDS, SweepSummary.class);
    } catch (Exception e) {
      return null; // still running, or the worker is down
    }
  }
}
