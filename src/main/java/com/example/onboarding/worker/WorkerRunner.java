package com.example.onboarding.worker;

import com.example.onboarding.activity.ComplianceActivitiesImpl;
import com.example.onboarding.activity.OnboardingActivitiesImpl;
import com.example.onboarding.registry.ContractorRegistry;
import com.example.onboarding.workflow.ComplianceSweepWorkflowImpl;
import com.example.onboarding.workflow.ContractorOnboardingWorkflowImpl;
import com.example.onboarding.workflow.CoverageRenewalWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Hosts all workflows and activities on a single task queue. Runs only in the worker role. */
@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class WorkerRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

  private final WorkflowClient client;
  private final ContractorRegistry registry;
  private final String taskQueue;
  private final long delayMs;
  private WorkerFactory factory;

  public WorkerRunner(
      WorkflowClient client,
      ContractorRegistry registry,
      @Value("${temporal.task-queue}") String taskQueue,
      @Value("${demo.activity-delay-ms}") long delayMs) {
    this.client = client;
    this.registry = registry;
    this.taskQueue = taskQueue;
    this.delayMs = delayMs;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    factory = WorkerFactory.newInstance(client);
    Worker worker = factory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(
        ContractorOnboardingWorkflowImpl.class,
        ComplianceSweepWorkflowImpl.class,
        CoverageRenewalWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        new OnboardingActivitiesImpl(registry, delayMs),
        new ComplianceActivitiesImpl(registry, client, taskQueue));
    factory.start();
    log.info("Worker started on task queue '{}'", taskQueue);
    new CountDownLatch(1).await(); // keep the process alive until it is stopped or killed
  }

  @PreDestroy
  public void stop() {
    if (factory != null) {
      factory.shutdown();
    }
  }
}
