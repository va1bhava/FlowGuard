package com.flowguard.Entity;
import jakarta.persistence.*;
import lombok.*;
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "plans")
public class Plans {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "requests_per_minute", nullable = false)
    private int requestsPerMinute;

    @Column(name = "requests_per_day", nullable = false)
    private int requestsPerDay;

    @Column(name = "price_inr")
    private int priceInr;

    public boolean isUnlimited() {
        return requestsPerMinute == -1;
    }
}
