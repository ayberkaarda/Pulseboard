package io.pulseboard.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.pulseboard.core.domain.shared.CredentialLookup;
import io.pulseboard.core.domain.shared.TenantScoped;
import io.pulseboard.core.domain.tenancy.TenantId;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** ArchUnit rules that keep every repository access to tenant-owned data scoped by {@link TenantId}. */
final class TenantScopedRepositoryRules {

    /**
     * The complete allow-list of unscoped credential lookups: declaring repository simple-name suffix to method
     * name. Anything else annotated with {@link CredentialLookup} is a violation.
     */
    static final Map<String, Set<String>> ALLOWED_CREDENTIAL_LOOKUPS = Map.of(
            "ApiKeyRepository", Set.of("findByKeyHash"),
            "RefreshTokenRepository", Set.of("findOwnerByTokenHash", "findByTokenHash"));

    private static final Set<String> PERSIST_METHODS = Set.of("save");

    /** Records returned by the allow-listed credential lookups; they exist to find the tenant of a credential. */
    private static final Set<String> CREDENTIAL_PROJECTIONS = Set.of("RefreshTokenOwner");

    private TenantScopedRepositoryRules() {
    }

    /** A repository is tenant-scoped when any of its method signatures involves a {@link TenantScoped} type. */
    static final DescribedPredicate<JavaClass> TENANT_SCOPED_REPOSITORY =
            new DescribedPredicate<>("repositories of @TenantScoped aggregates") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return javaClass.isInterface()
                            && javaClass.getSimpleName().endsWith("Repository")
                            && javaClass.getAllMethods().stream()
                                    .anyMatch(TenantScopedRepositoryRules::involvesTenantScoped);
                }
            };

    /**
     * Every method of a tenant-scoped repository takes a {@link TenantId}, except {@code save(entity)} (the
     * entity carries its immutable tenant) and allow-listed {@link CredentialLookup} methods.
     */
    static ArchRule repositoryMethodsRequireTenantId() {
        return classes().that(TENANT_SCOPED_REPOSITORY)
                .should(new ArchCondition<>("declare a TenantId parameter on every query or modifying method") {
                    @Override
                    public void check(JavaClass repository, ConditionEvents events) {
                        for (JavaMethod method : repository.getMethods()) {
                            if (method.isAnnotatedWith(CredentialLookup.class) || isPersistMethod(method)) {
                                continue;
                            }
                            boolean scoped = method.getRawParameterTypes().stream()
                                    .anyMatch(type -> type.isEquivalentTo(TenantId.class));
                            String message = method.getFullName() + (scoped
                                    ? " is tenant-scoped"
                                    : " has no TenantId parameter although it accesses tenant-owned data");
                            events.add(new SimpleConditionEvent(method, scoped, message));
                        }
                    }
                });
    }

    /** {@link CredentialLookup} is only allowed on the pinned set of credential-hash lookups. */
    static ArchRule credentialLookupsArePinned() {
        return methods().that().areAnnotatedWith(CredentialLookup.class)
                .should(new ArchCondition<>("be one of the allow-listed credential hash lookups") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        boolean allowed = ALLOWED_CREDENTIAL_LOOKUPS.entrySet().stream()
                                .anyMatch(entry -> method.getOwner().getSimpleName().endsWith(entry.getKey())
                                        && entry.getValue().contains(method.getName()));
                        events.add(new SimpleConditionEvent(method, allowed,
                                method.getFullName() + (allowed ? " is allow-listed" : " is not an allowed lookup")));
                    }
                })
                .allowEmptyShould(false);
    }

    /** Entities with a tenant column must be marked {@link TenantScoped} so the repository rule applies. */
    static ArchRule entitiesWithTenantIdAreTenantScoped() {
        return classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .and(new DescribedPredicate<>("have a TenantId field") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return javaClass.getFields().stream()
                                .anyMatch(field -> field.getRawType().isEquivalentTo(TenantId.class));
                    }
                })
                .should().beAnnotatedWith(TenantScoped.class);
    }

    /**
     * Read models that are plain records (not JPA entities) are tenant-scoped data just the same: a record with a
     * {@link TenantId} component must be marked {@link TenantScoped}, otherwise its repository would escape the
     * rule that every query takes a tenant.
     */
    static ArchRule recordsWithTenantIdAreTenantScoped() {
        return classes().that().resideInAPackage("io.pulseboard.core.domain..")
                .and().areRecords()
                .and(new DescribedPredicate<>("have a TenantId component") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return javaClass.getFields().stream()
                                .anyMatch(field -> field.getRawType().isEquivalentTo(TenantId.class));
                    }
                })
                .and().areNotAssignableTo(TenantId.class)
                .and(new DescribedPredicate<JavaClass>("are not a credential lookup projection") {
                    @Override
                    public boolean test(JavaClass javaClass) {
                        return !CREDENTIAL_PROJECTIONS.contains(javaClass.getSimpleName());
                    }
                })
                .should().beAnnotatedWith(TenantScoped.class);
    }

    private static boolean isPersistMethod(JavaMethod method) {
        return PERSIST_METHODS.contains(method.getName())
                && method.getRawParameterTypes().size() == 1
                && method.getRawParameterTypes().getFirst().isAnnotatedWith(TenantScoped.class);
    }

    private static boolean involvesTenantScoped(JavaMethod method) {
        return Stream.concat(Stream.of(method.getReturnType()), method.getParameterTypes().stream())
                .map(JavaType::getAllInvolvedRawTypes)
                .flatMap(Set::stream)
                .anyMatch(type -> type.isAnnotatedWith(TenantScoped.class));
    }
}
