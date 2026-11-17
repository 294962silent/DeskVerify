package com.echomind.saas.arch;

import com.echomind.saas.api.archfixture.InvalidApiJdbcDependency;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaasApiPersistenceBanTest {

    @Test
    void apiPersistenceRuleRejectsADirectJdbcTemplateDependency() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(InvalidApiJdbcDependency.class);

        assertThatThrownBy(() -> ModuleBoundaryTest
                .saas_api_does_not_depend_on_persistence_or_infrastructure
                .check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JdbcTemplate");
    }
}
