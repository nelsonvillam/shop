package com.example.shop.config;

import com.example.shop.model.Address;
import com.example.shop.model.Customer;
import com.example.shop.model.Order;
import com.example.shop.model.Product;
import com.example.shop.model.Role;
import com.example.shop.model.User;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.repository.OrderRepository;
import com.example.shop.repository.ProductRepository;
import com.example.shop.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@org.springframework.core.annotation.Order(2)
public class DataLoader implements ApplicationRunner {

    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public DataLoader(CustomerRepository customerRepository,
                      ProductRepository productRepository,
                      OrderRepository orderRepository,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.admin.password:}") String adminPassword) {
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!adminPassword.isBlank() && userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setRole(Role.ROLE_ADMIN);
            userRepository.save(admin);
            log.info("Seeded admin user");
        }

        if (customerRepository.count() > 0) {
            log.info("Database already seeded — skipping DataLoader");
            return;
        }

        log.info("Seeding database...");

        // ONE-TO-ONE: each customer embeds a single Address
        Customer alice = customerRepository.save(new Customer(null, "Alice Johnson", "alice@example.com",
                new Address("123 Main St", "New York", "USA", "10001")));

        Customer bob = customerRepository.save(new Customer(null, "Bob Martinez", "bob@example.com",
                new Address("456 Oak Ave", "Los Angeles", "USA", "90001")));

        Customer carol = customerRepository.save(new Customer(null, "Carol Smith", "carol@example.com",
                new Address("789 Pine Rd", "Chicago", "USA", "60601")));

        // Products
        Product laptop   = productRepository.save(new Product(null, "Laptop Pro 15",     "High-performance laptop",   1299.99, 50));
        Product mouse    = productRepository.save(new Product(null, "Wireless Mouse",     "Ergonomic wireless mouse",    39.99, 200));
        Product keyboard = productRepository.save(new Product(null, "Mechanical Keyboard","RGB mechanical keyboard",     89.99, 150));
        Product monitor  = productRepository.save(new Product(null, "4K Monitor",         "27-inch 4K display",         449.99, 75));

        // ONE-TO-MANY : Alice has 2 orders (customerId stored as ObjectId)
        // MANY-TO-MANY: Keyboard appears in Alice's, Bob's and Carol's orders
        orderRepository.saveAll(List.of(
                order(alice, List.of(laptop, mouse)),
                order(alice, List.of(keyboard)),
                order(bob,   List.of(monitor, keyboard)),
                order(carol, List.of(laptop, keyboard, monitor))
        ));

        log.info("Seeded {} customers, {} products, {} orders",
                customerRepository.count(),
                productRepository.count(),
                orderRepository.count());
    }

    private Order order(Customer customer, List<Product> products) {
        double total = products.stream().mapToDouble(Product::getPrice).sum();
        List<ObjectId> productIds = products.stream()
                .map(p -> new ObjectId(p.getId()))
                .toList();
        Order order = new Order();
        order.setCustomerId(new ObjectId(customer.getId()));
        order.setProductIds(productIds);
        order.setTotal(total);
        return order;
    }
}
