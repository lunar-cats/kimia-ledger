package com.kimialedger.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boot entry point. Wiring only; no logic lives in this module. */
@SpringBootApplication(scanBasePackages = "com.kimialedger")
public class LedgerServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerServerApplication.class, args);
  }
}
