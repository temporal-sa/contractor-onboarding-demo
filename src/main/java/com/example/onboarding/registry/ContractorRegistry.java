package com.example.onboarding.registry;

import com.example.onboarding.model.Models.Contractor;
import com.example.onboarding.model.Models.ExpiringCoverage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simulated contractor system of record. File-backed so the api and worker processes share it
 * and it survives a worker restart. In a real integration only this class would change.
 */
@Component
public class ContractorRegistry {

  private final ObjectMapper mapper = new ObjectMapper();
  private final Path path;

  public ContractorRegistry(@Value("${demo.registry-path}") String registryPath) {
    this.path = Path.of(registryPath);
  }

  @PostConstruct
  public synchronized void seedIfMissing() throws IOException {
    if (Files.exists(path)) {
      return;
    }
    LocalDate today = LocalDate.now();
    List<Contractor> seed = new ArrayList<>();
    seed.add(contractor("C-1001", "Apex Plumbing Co", "Plumbing", today.plusDays(6), today.plusDays(300)));
    seed.add(contractor("C-1002", "BrightSpark Electric", "Electrical", today.plusDays(200), today.plusDays(14)));
    seed.add(contractor("C-1003", "Cool Breeze HVAC", "HVAC", today.plusDays(25), today.plusDays(120)));
    seed.add(contractor("C-1004", "Dependable Roofing", "Roofing", today.plusDays(400), today.plusDays(400)));
    write(seed);
  }

  public synchronized List<Contractor> list() {
    return read();
  }

  public synchronized void add(Contractor c) {
    List<Contractor> all = read();
    all.removeIf(x -> x.id.equals(c.id));
    all.add(c);
    write(all);
  }

  public synchronized Contractor find(String id) {
    for (Contractor c : read()) {
      if (c.id.equals(id)) {
        return c;
      }
    }
    return null;
  }

  public synchronized List<ExpiringCoverage> findExpiring(int windowDays) {
    LocalDate cutoff = LocalDate.now().plusDays(windowDays);
    List<ExpiringCoverage> out = new ArrayList<>();
    for (Contractor c : read()) {
      if (!"ACTIVE".equals(c.status)) {
        continue;
      }
      if (!LocalDate.parse(c.insuranceExpiry).isAfter(cutoff)) {
        out.add(coverage(c, "INSURANCE", c.insuranceExpiry));
      }
      if (!LocalDate.parse(c.licenseExpiry).isAfter(cutoff)) {
        out.add(coverage(c, "LICENSE", c.licenseExpiry));
      }
    }
    return out;
  }

  public synchronized void updateCoverage(String id, String coverageType, String newExpiry) {
    List<Contractor> all = read();
    for (Contractor c : all) {
      if (c.id.equals(id)) {
        if ("INSURANCE".equals(coverageType)) {
          c.insuranceExpiry = newExpiry;
          c.insuranceLastRenewal = "RENEWED";
        } else {
          c.licenseExpiry = newExpiry;
          c.licenseLastRenewal = "RENEWED";
        }
      }
    }
    write(all);
  }

  public synchronized void suspend(String id) {
    List<Contractor> all = read();
    for (Contractor c : all) {
      if (c.id.equals(id)) {
        c.status = "SUSPENDED";
      }
    }
    write(all);
  }

  // ------------------------------------------------------------------ helpers

  private static Contractor contractor(
      String id, String name, String trade, LocalDate insurance, LocalDate license) {
    Contractor c = new Contractor();
    c.id = id;
    c.name = name;
    c.trade = trade;
    c.insuranceExpiry = insurance.toString();
    c.licenseExpiry = license.toString();
    return c;
  }

  private static ExpiringCoverage coverage(Contractor c, String type, String expiry) {
    ExpiringCoverage e = new ExpiringCoverage();
    e.contractorId = c.id;
    e.contractorName = c.name;
    e.coverageType = type;
    e.expiry = expiry;
    return e;
  }

  private List<Contractor> read() {
    try {
      return mapper.readValue(path.toFile(), new TypeReference<List<Contractor>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read registry at " + path, e);
    }
  }

  private void write(List<Contractor> all) {
    try {
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent());
      }
      Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot write registry at " + path, e);
    }
  }
}
