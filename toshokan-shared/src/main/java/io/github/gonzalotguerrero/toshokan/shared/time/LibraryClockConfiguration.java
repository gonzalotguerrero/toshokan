package io.github.gonzalotguerrero.toshokan.shared.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The library's clock. Due dates and "is this overdue?" are calendar questions, so they're answered in the
 * library's own time zone, set with {@code toshokan.time-zone}. Without it, the server's zone is used.
 */
@Configuration
public class LibraryClockConfiguration {

    @Bean
    public Clock libraryClock(@Value("${toshokan.time-zone:}") String timeZone) {
        ZoneId zone = timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone);
        return Clock.system(zone);
    }
}
