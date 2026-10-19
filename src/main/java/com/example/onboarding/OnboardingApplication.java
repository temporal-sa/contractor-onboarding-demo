package com.example.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One jar, two roles. Run with --spring.profiles.active=api for the REST API and front end,
 * or --spring.profiles.active=worker for the Temporal worker (a separate process you can kill).
 */
@SpringBootApplication
public class OnboardingApplication {
  public static void main(String[] args) {
    SpringApplication.run(OnboardingApplication.class, args);
  }
}
