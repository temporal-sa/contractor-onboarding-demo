package com.example.onboarding.workflow;

import java.time.Duration;

/** Onboarding stages with owning team and real-time SLA (before timescale is applied). */
public enum Stage {
  INTAKE("Contractor Ops", null),
  SCREENING("Compliance", Duration.ofDays(1)),
  LICENSE("Compliance", Duration.ofDays(2)),
  INSURANCE("Risk", Duration.ofDays(2)),
  RISK("Risk", Duration.ofDays(2)),
  BANKING("Finance and AP", Duration.ofDays(3)),
  CONTRACTING("Legal", Duration.ofDays(5)),
  ONBOARDING("Contractor Ops", null);

  public final String team;
  public final Duration sla;

  Stage(String team, Duration sla) {
    this.team = team;
    this.sla = sla;
  }
}
