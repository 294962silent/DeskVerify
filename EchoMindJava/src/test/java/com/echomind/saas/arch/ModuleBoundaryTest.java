package com.echomind.saas.arch;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.echomind.saas")
class ModuleBoundaryTest {

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
