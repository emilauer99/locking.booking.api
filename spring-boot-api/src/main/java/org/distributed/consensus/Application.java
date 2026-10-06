package org.distributed.consensus;

import java.sql.Date;
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
    public CommandLineRunner seedBookings(BookingRepository bookingRepository) {
        return args -> {
            // only seed an empty database, otherwise every restart (or every further
            // api instance) would add the same bookings again
            if (bookingRepository.count() > 0) {
                return;
            }

            bookingRepository.saveAll(List.of(
                    new Booking("Blue Room", 1, Date.valueOf("2026-11-02"), Date.valueOf("2026-11-06")),
                    new Booking("Blue Room", 1, Date.valueOf("2026-11-20"), Date.valueOf("2026-11-23")),
                    new Booking("Green Room", 2, Date.valueOf("2026-11-10"), Date.valueOf("2026-11-14")),
                    new Booking("Red Suite", 3, Date.valueOf("2026-12-22"), Date.valueOf("2026-12-27")),
                    new Booking("Garden View", 4, Date.valueOf("2027-01-05"), Date.valueOf("2027-01-09"))));

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