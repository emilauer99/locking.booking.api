package org.distributed.consensus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.distributed.consensus.model.Booking;
import org.distributed.consensus.repository.BookingRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
        return args -> {

            System.out.println("Let's inspect the beans provided by Spring Boot:");

            String[] beanNames = ctx.getBeanDefinitionNames();
            Arrays.sort(beanNames);
            for (String beanName : beanNames) {
                System.out.println(beanName);
            }
        };
    }

    @Bean
    public Clock clock() {
        // a single source of time, so tests can replace it (e.g. to let reservations expire)
        return Clock.systemUTC();
    }

    @Bean
    public CommandLineRunner seedBookings(BookingRepository bookingRepository, Clock clock) {
        return args -> {
            // only seed an empty database, otherwise every restart (or every further
            // api instance) would add the same bookings again
            if (bookingRepository.count() > 0) {
                return;
            }

            // sample data is seeded as confirmed, otherwise it would expire after a few minutes
            Instant now = clock.instant();
            bookingRepository.saveAll(List.of(
                    Booking.confirmed("Blue Room", 1, LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-06"), now),
                    Booking.confirmed("Blue Room", 1, LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-23"), now),
                    Booking.confirmed("Green Room", 2, LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-14"), now),
                    Booking.confirmed("Red Suite", 3, LocalDate.parse("2026-12-22"), LocalDate.parse("2026-12-27"), now),
                    Booking.confirmed("Garden View", 4, LocalDate.parse("2027-01-05"), LocalDate.parse("2027-01-09"), now)));

            System.out.println("Seeded database with sample bookings.");
        };
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**").allowedMethods("*");
            }
        };
    }
}