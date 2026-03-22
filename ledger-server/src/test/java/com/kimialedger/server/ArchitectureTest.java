package com.kimialedger.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the hexagonal dependency rules from CLAUDE.md. A failure here is a design defect; fix
 * the code, never the rule.
 */
@AnalyzeClasses(packages = "com.kimialedger", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String CORE = "com.kimialedger.core..";
  private static final String APPLICATION = "com.kimialedger.application..";
  private static final String PERSISTENCE = "com.kimialedger.persistence..";
  private static final String API_REST = "com.kimialedger.api.rest..";
  private static final String API_GRPC = "com.kimialedger.api.grpc..";
  private static final String SERVER = "com.kimialedger.server..";

  @ArchTest
  static final ArchRule dependencies_point_inward =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .withOptionalLayers(true) // modules are still empty in Phase 1
          .layer("Core")
          .definedBy(CORE)
          .layer("Application")
          .definedBy(APPLICATION)
          .layer("Persistence")
          .definedBy(PERSISTENCE)
          .layer("RestApi")
          .definedBy(API_REST)
          .layer("GrpcApi")
          .definedBy(API_GRPC)
          .layer("Server")
          .definedBy(SERVER)
          .whereLayer("Core")
          .mayNotAccessAnyLayer()
          .whereLayer("Application")
          .mayOnlyAccessLayers("Core")
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Persistence", "RestApi", "GrpcApi", "Server")
          .whereLayer("Persistence")
          .mayOnlyBeAccessedByLayers("Server")
          .whereLayer("RestApi")
          .mayOnlyBeAccessedByLayers("Server")
          .whereLayer("GrpcApi")
          .mayOnlyBeAccessedByLayers("Server")
          .whereLayer("Server")
          .mayNotBeAccessedByAnyLayer();

  @ArchTest
  static final ArchRule core_depends_on_jdk_only =
      classes()
          .that()
          .resideInAPackage(CORE)
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(CORE, "java..")
          .allowEmptyShould(true); // ponytail: drop once the first domain class lands (Phase 2)

  @ArchTest
  static final ArchRule core_and_application_are_framework_free =
      noClasses()
          .that()
          .resideInAnyPackage(CORE, APPLICATION)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "javax..",
              "java.sql..",
              "com.google.protobuf..",
              "io.grpc..",
              "org.flywaydb..",
              "oracle..")
          .allowEmptyShould(true); // ponytail: drop once the first domain class lands (Phase 2)

  @ArchTest
  static final ArchRule sql_lives_only_in_persistence =
      noClasses()
          .that()
          .resideOutsideOfPackages(PERSISTENCE, SERVER)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..", "oracle..")
          .allowEmptyShould(true);
}
