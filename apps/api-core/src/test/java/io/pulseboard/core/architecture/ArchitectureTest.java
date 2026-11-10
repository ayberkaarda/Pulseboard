package io.pulseboard.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import io.pulseboard.core.architecture.fixtures.UnscopedServiceRepository;
import io.pulseboard.core.domain.alert.AlertEventRepository;
import io.pulseboard.core.domain.alert.AlertOutbox;
import io.pulseboard.core.domain.alert.AlertRuleRepository;
import io.pulseboard.core.domain.anomaly.AnomalyRepository;
import io.pulseboard.core.domain.catalog.Service;
import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules: {@code api -> application -> domain <- infrastructure}, a Spring-free domain, and
 * tenant-scoped repositories.
 */
class ArchitectureTest {

    private static final String ROOT = "io.pulseboard.core";

    private static JavaClasses production;

    @BeforeAll
    static void importClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void layersOnlyDependInwards() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Api").definedBy(ROOT + ".api..")
                .layer("Application").definedBy(ROOT + ".application..")
                .layer("Domain").definedBy(ROOT + ".domain..")
                .layer("Infrastructure").definedBy(ROOT + ".infrastructure..")
                .whereLayer("Api").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Api", "Infrastructure")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Application", "Infrastructure")
                .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
                .check(production);
    }

    @Test
    void domainHasNoSpringDependencies() {
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .check(production);
    }

    @Test
    void applicationDoesNotDependOnWebOrPersistenceFrameworkTypes() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web.bind..", "org.springframework.data..", "jakarta.servlet..")
                .check(production);
    }

    @Test
    void tenantScopedRepositoryMethodsRequireTenantId() {
        TenantScopedRepositoryRules.repositoryMethodsRequireTenantId().check(production);
    }

    @Test
    void ruleCoversEveryTenantScopedRepository() {
        assertThat(production.stream()
                .filter(TenantScopedRepositoryRules.TENANT_SCOPED_REPOSITORY)
                .map(JavaClass::getSimpleName))
                .contains("UserRepository", "ApiKeyRepository", "RefreshTokenRepository", "ServiceRepository",
                        "MetricDefinitionRepository", "JpaServiceRepository", "JpaRefreshTokenRepository",
                        "MetricSampleRepository", "AnomalyRepository", "AlertRuleRepository", "AlertEventRepository")
                .doesNotContain("TenantRepository");
    }

    @Test
    void bulkWritesNeverGoThroughJpaSaveAll() {
        // Metric ingestion is a hot path: JDBC batch upserts only, no ORM bulk saves.
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().callMethodWhere(JavaCall.Predicates.target(HasName.Predicates.name("saveAll")))
                .check(production);
    }

    @Test
    void credentialLookupsArePinned() {
        TenantScopedRepositoryRules.credentialLookupsArePinned().check(production);
    }

    @Test
    void entitiesWithTenantIdAreTenantScoped() {
        TenantScopedRepositoryRules.entitiesWithTenantIdAreTenantScoped().check(production);
    }

    @Test
    void recordsWithTenantIdAreTenantScoped() {
        TenantScopedRepositoryRules.recordsWithTenantIdAreTenantScoped().check(production);
        assertThat(production.stream()
                .filter(javaClass -> javaClass.isRecord() && javaClass.isAnnotatedWith(TenantScoped.class))
                .map(JavaClass::getSimpleName))
                .contains("Anomaly", "AlertRule", "AlertEvent", "DueAlert");
    }

    @Test
    void anomalyQueriesRequireATenant() {
        JavaClass repository = production.get(AnomalyRepository.class);

        assertThat(repository.getMethods()).isNotEmpty().allSatisfy(method ->
                assertThat(method.getRawParameterTypes()).as(method.getFullName())
                        .anyMatch(type -> type.isEquivalentTo(TenantId.class)));
    }

    @Test
    void alertQueriesRequireATenant() {
        for (Class<?> repository : List.of(AlertRuleRepository.class, AlertEventRepository.class)) {
            assertThat(production.get(repository).getMethods()).isNotEmpty().allSatisfy(method ->
                    assertThat(method.getRawParameterTypes()).as(method.getFullName())
                            .anyMatch(type -> type.isEquivalentTo(TenantId.class)));
        }
    }

    @Test
    void theOutboxOnlyClaimsAcrossTenantsAndEveryStateChangeNamesTheTenant() {
        assertThat(production.get(AlertOutbox.class).getMethods()).isNotEmpty().allSatisfy(method -> {
            if (!method.getName().equals("claimDue")) {
                assertThat(method.getRawParameterTypes()).as(method.getFullName())
                        .anyMatch(type -> type.isEquivalentTo(TenantId.class));
            }
        });
    }

    @Test
    void repositoryRuleRejectsAnUnscopedQuery() {
        JavaClasses fixture = new ClassFileImporter().importClasses(UnscopedServiceRepository.class, Service.class);

        EvaluationResult result = TenantScopedRepositoryRules.repositoryMethodsRequireTenantId().evaluate(fixture);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(detail -> detail.contains("UnscopedServiceRepository.findById("))
                .noneMatch(detail -> detail.contains("findByIdAndTenantId"));
    }
}
