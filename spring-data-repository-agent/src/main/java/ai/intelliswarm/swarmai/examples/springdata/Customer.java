package ai.intelliswarm.swarmai.examples.springdata;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Demo JPA entity for the SpringDataRepositoryTool showcase. */
@Entity
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String email;
    private String name;
    private int lifetimeSpendCents;
    private String tier;

    public Customer() {}

    public Customer(Long id, String email, String name, int lifetimeSpendCents, String tier) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.lifetimeSpendCents = lifetimeSpendCents;
        this.tier = tier;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public int getLifetimeSpendCents() { return lifetimeSpendCents; }
    public String getTier() { return tier; }
}
