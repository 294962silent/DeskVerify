package com.echomind.saas.arch;

import com.echomind.saas.architecture.LegacySaaSBridge;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.echomind", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule legacy_code_uses_only_explicit_saas_bridges = noClasses()
            .that().resideOutsideOfPackages("com.echomind.saas..")
            .and().areNotAnnotatedWith(LegacySaaSBridge.class)
            .should().dependOnClassesThat().resideInAPackage("com.echomind.saas..");

    @ArchTest
    static final ArchRule saas_api_does_not_depend_on_persistence_or_infrastructure = noClasses()
            .that().resideInAPackage("com.echomind.saas.api..")
            // This handler is the stable translation boundary for typed platform failures.
            .and().doNotHaveSimpleName("GlobalApiExceptionHandler")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..",
                    "javax.sql..",
                    "org.springframework.jdbc..",
                    "org.springframework.data..",
                    "com.echomind.saas.platform..",
                    "com.echomind.saas.identity.infrastructure..",
                    "com.echomind.saas.infrastructure..",
                    "com.echomind.saas..infrastructure.."
            );

    @ArchTest
    static final ArchRule saas_domain_does_not_depend_on_persistence = noClasses()
            .that().resideInAPackage("com.echomind.saas..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..",
                    "javax.sql..",
                    "org.springframework.jdbc..",
                    "org.springframework.data..",
                    "com.echomind.saas.identity.infrastructure.."
            );

    @ArchTest
    static final ArchRule identity_does_not_depend_on_business_modules = noClasses()
            .that().resideInAPackage("com.echomind.saas.identity..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.echomind.saas.api..",
                    "com.echomind.saas.commerce..",
                    "com.echomind.saas.support..",
                    "com.echomind.saas.ticket..",
                    "com.echomind.saas.knowledge..",
                    "com.echomind.saas.agent..",
                    "com.echomind.saas.evaluation.."
            );
}
