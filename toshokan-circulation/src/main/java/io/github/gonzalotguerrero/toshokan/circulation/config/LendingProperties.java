package io.github.gonzalotguerrero.toshokan.circulation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Period;

/**
 * The lending rules, so the library can change them in {@code application.properties} without touching code.
 *
 * @param loanPeriod     how long a loan lasts, and how far a renewal pushes the due date from today
 * @param maxRenewals    how many times one loan can be renewed
 * @param maxActiveLoans how many books a member can have out at once
 */
@ConfigurationProperties("toshokan.lending")
public record LendingProperties(
        @DefaultValue("14d") Period loanPeriod,
        @DefaultValue("2") int maxRenewals,
        @DefaultValue("3") int maxActiveLoans
) {

    public LendingProperties {
        // Better to refuse to start than to lend books under rules that make no sense
        if (loanPeriod.isNegative() || loanPeriod.isZero()) {
            throw new IllegalArgumentException("toshokan.lending.loan-period must be longer than zero");
        }
        if (maxRenewals < 0) {
            throw new IllegalArgumentException("toshokan.lending.max-renewals can't be negative");
        }
        if (maxActiveLoans < 1) {
            throw new IllegalArgumentException("toshokan.lending.max-active-loans must be at least 1");
        }
    }
}
