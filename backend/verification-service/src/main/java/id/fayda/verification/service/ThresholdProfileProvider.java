package id.fayda.verification.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import id.fayda.verification.domain.entity.ThresholdProfileEntity;
import id.fayda.verification.infra.repo.ThresholdProfileRepository;
import id.fayda.verification.service.decision.ThresholdProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where the decision numbers come from (CONTRACT §4): "weights and thresholds come from the
 * active {@code threshold_profiles} row".
 *
 * <p>Reads the newest active row when a database answers; falls back to the bundled
 * {@code threshold-defaults.yml} when the table is empty, unreadable or absent — which is what
 * makes the whole service testable without MS SQL. Either way {@link ThresholdProfile#version()}
 * is carried into the decision, so every {@code decision_records.threshold_version} is honest
 * about the profile that produced it.</p>
 */
@Service
public class ThresholdProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(ThresholdProfileProvider.class);
    private static final String BUNDLED_RESOURCE = "threshold-defaults.yml";
    private static final String BUNDLED_ROOT = "thresholds.default";

    private final ThresholdProfileRepository repository;
    private final ThresholdProfile bundledDefault;

    public ThresholdProfileProvider(ThresholdProfileRepository repository) {
        this.repository = repository;
        this.bundledDefault = loadBundledDefault();
    }

    @Transactional(readOnly = true)
    public ThresholdProfile activeProfile() {
        try {
            List<ThresholdProfileEntity> active = repository.findActiveProfiles();
            if (!active.isEmpty()) {
                return toValueObject(active.get(0));
            }
            log.debug("no active threshold_profiles row; using the bundled default {}",
                    bundledDefault.version());
        } catch (DataAccessException e) {
            log.warn("threshold_profiles is unreadable ({}); falling back to the bundled default {}",
                    e.getClass().getSimpleName(), bundledDefault.version());
        }
        return bundledDefault;
    }

    /** Exposed for tests and for the health/observability surface. */
    public ThresholdProfile bundledDefault() {
        return bundledDefault;
    }

    private ThresholdProfile toValueObject(ThresholdProfileEntity row) {
        try {
            return new ThresholdProfile(row.getVersion(), row.getWeightLiveness(),
                    row.getWeightMatch(), row.getWeightDocument(), row.getPassComposite(),
                    row.getReviewComposite(), row.getMinLiveness(), row.getMinMatch(),
                    row.getMaxAttempts());
        } catch (IllegalArgumentException e) {
            // A mis-calibrated row must not decide identities on numbers we cannot trust.
            log.error("active threshold profile {} is invalid ({}); using bundled default {}",
                    row.getVersion(), e.getMessage(), bundledDefault.version());
            return bundledDefault;
        }
    }

    @SuppressWarnings("unchecked")
    private static ThresholdProfile loadBundledDefault() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load(BUNDLED_RESOURCE, new ClassPathResource(BUNDLED_RESOURCE));
            if (sources.isEmpty()) {
                throw new IllegalStateException(BUNDLED_RESOURCE + " produced no property source");
            }
            // A YAML source's values are OriginTrackedValue wrappers, which no binder can
            // convert to int — unwrap them into a plain map first.
            Map<String, Object> flat = new LinkedHashMap<>();
            ((Map<String, Object>) sources.get(0).getSource())
                    .forEach((key, value) -> flat.put(key,
                            value instanceof OriginTrackedValue tracked ? tracked.getValue() : value));
            Binder binder = new Binder(new MapConfigurationPropertySource(flat));
            BundledProfile parsed = binder.bind(BUNDLED_ROOT, BundledProfile.class)
                    .orElseThrow(() -> new IllegalStateException(
                            BUNDLED_ROOT + " is missing from " + BUNDLED_RESOURCE));
            return parsed.toValueObject();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + BUNDLED_RESOURCE, e);
        }
    }

    /** JavaBean target for the bundled YAML; kept separate from the immutable value object. */
    public static class BundledProfile {
        private String version;
        private BigDecimal weightLiveness;
        private BigDecimal weightMatch;
        private BigDecimal weightDocument;
        private BigDecimal passComposite;
        private BigDecimal reviewComposite;
        private BigDecimal minLiveness;
        private BigDecimal minMatch;
        private int maxAttempts;
        private String calibratedOn;

        public ThresholdProfile toValueObject() {
            return new ThresholdProfile(version, weightLiveness, weightMatch, weightDocument,
                    passComposite, reviewComposite, minLiveness, minMatch, maxAttempts);
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public BigDecimal getWeightLiveness() {
            return weightLiveness;
        }

        public void setWeightLiveness(BigDecimal weightLiveness) {
            this.weightLiveness = weightLiveness;
        }

        public BigDecimal getWeightMatch() {
            return weightMatch;
        }

        public void setWeightMatch(BigDecimal weightMatch) {
            this.weightMatch = weightMatch;
        }

        public BigDecimal getWeightDocument() {
            return weightDocument;
        }

        public void setWeightDocument(BigDecimal weightDocument) {
            this.weightDocument = weightDocument;
        }

        public BigDecimal getPassComposite() {
            return passComposite;
        }

        public void setPassComposite(BigDecimal passComposite) {
            this.passComposite = passComposite;
        }

        public BigDecimal getReviewComposite() {
            return reviewComposite;
        }

        public void setReviewComposite(BigDecimal reviewComposite) {
            this.reviewComposite = reviewComposite;
        }

        public BigDecimal getMinLiveness() {
            return minLiveness;
        }

        public void setMinLiveness(BigDecimal minLiveness) {
            this.minLiveness = minLiveness;
        }

        public BigDecimal getMinMatch() {
            return minMatch;
        }

        public void setMinMatch(BigDecimal minMatch) {
            this.minMatch = minMatch;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public String getCalibratedOn() {
            return calibratedOn;
        }

        public void setCalibratedOn(String calibratedOn) {
            this.calibratedOn = calibratedOn;
        }
    }
}
