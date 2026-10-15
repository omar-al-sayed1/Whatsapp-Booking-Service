package app.clinicreminders;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.common.CrossTenant;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Multi-tenancy guard. A repository for an entity with a {@code clinicId} must extend only
 * {@link Repository} (so it inherits no unscoped findById/findAll), and every method must take a
 * {@code clinicId} parameter unless it writes an entity or is marked {@link CrossTenant}.
 */
class TenantRepositoryConventionTest {

    private static final Set<String> ENTITY_WRITES = Set.of("save", "saveAll", "saveAndFlush", "delete");

    @Test
    void everyTenantRepositoryMethodIsScopedToAClinic() {
        List<Class<?>> repositories = applicationRepositories();
        assertThat(repositories).contains(DoctorRepository.class, AppointmentRepository.class);

        List<String> violations = repositories.stream().flatMap(r -> violationsOf(r).stream()).toList();

        assertThat(violations).isEmpty();
    }

    @Test
    void flagsUnscopedQueriesAndInheritedCrudMethods() {
        assertThat(violationsOf(UnscopedQuery.class)).containsExactly("UnscopedQuery.findById has no clinicId parameter");
        assertThat(violationsOf(InheritsCrud.class)).contains(
                "InheritsCrud must extend only Repository, so it inherits no unscoped queries",
                "InheritsCrud.findAll has no clinicId parameter");
        assertThat(violationsOf(Sanctioned.class)).isEmpty();
    }

    private static List<String> violationsOf(Class<?> repository) {
        Class<?> domainType = ResolvableType.forClass(repository).as(Repository.class).getGeneric(0).resolve();
        if (domainType == null || ReflectionUtils.findField(domainType, "clinicId") == null) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        String name = repository.getSimpleName();
        if (!Arrays.equals(repository.getInterfaces(), new Class<?>[] {Repository.class})) {
            violations.add(name + " must extend only Repository, so it inherits no unscoped queries");
        }
        for (Method method : repository.getMethods()) {
            if (ENTITY_WRITES.contains(method.getName()) || method.isAnnotationPresent(CrossTenant.class)) {
                continue;
            }
            boolean scoped = Arrays.stream(method.getParameters()).anyMatch(p -> p.getName().equals("clinicId"));
            if (!scoped) {
                violations.add(name + "." + method.getName() + " has no clinicId parameter");
            }
        }
        return violations.stream().distinct().toList();
    }

    private static List<Class<?>> applicationRepositories() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        return scanner.findCandidateComponents("app.clinicreminders").stream()
                .map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), null))
                .filter(type -> type.getEnclosingClass() != TenantRepositoryConventionTest.class)
                .<Class<?>>map(type -> type)
                .toList();
    }

    interface UnscopedQuery extends Repository<Doctor, Long> {

        Optional<Doctor> findById(Long id);

        Doctor save(Doctor doctor);
    }

    interface InheritsCrud extends CrudRepository<Doctor, Long> {
    }

    interface Sanctioned extends Repository<Doctor, Long> {

        @CrossTenant("test")
        List<Doctor> findByActiveTrue();
    }
}
