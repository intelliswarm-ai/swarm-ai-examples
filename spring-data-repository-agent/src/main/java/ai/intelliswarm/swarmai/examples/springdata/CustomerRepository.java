package ai.intelliswarm.swarmai.examples.springdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Demo repository the agent will reflectively discover + call through
 * {@link ai.intelliswarm.swarmai.tool.data.repository.SpringDataRepositoryTool}.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    List<Customer> findByTier(String tier);
    List<Customer> findByLifetimeSpendCentsLessThan(int cents);
    List<Customer> findByLifetimeSpendCentsGreaterThanEqual(int cents);
    List<Customer> findByNameContainingIgnoreCase(String fragment);
    long countByTier(String tier);
}
