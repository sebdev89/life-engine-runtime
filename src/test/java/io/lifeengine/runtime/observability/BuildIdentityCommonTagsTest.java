package io.lifeengine.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Micrometer common tags (KAN-195 acceptance): every meter carries {@code environment},
 * {@code service}, {@code version}, {@code commit}. Applies the real customizer bean to a
 * {@link SimpleMeterRegistry} — no context needed.
 */
class BuildIdentityCommonTagsTest {

    private static Meter firstMeterAfterTagging(BuildIdentity id) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BuildIdentityConfig().buildIdentityCommonTags(id).customize(registry);
        registry.counter("demo.counter").increment();
        return registry.getMeters().get(0);
    }

    @Test
    @DisplayName("every meter carries environment/service/version/commit")
    void everyMeterCarriesIdentityTags() {
        BuildIdentity id =
                new BuildIdentity("life-engine-runtime", "0.1.0-SNAPSHOT", "uat", "215e56a", "t", "main", "b");

        Meter.Id m = firstMeterAfterTagging(id).getId();

        assertThat(m.getTag("environment")).isEqualTo("uat");
        assertThat(m.getTag("service")).isEqualTo("life-engine-runtime");
        assertThat(m.getTag("version")).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(m.getTag("commit")).isEqualTo("215e56a");
    }

    @Test
    @DisplayName("absent commit degrades to 'unknown' (Micrometer rejects null tag values)")
    void nullCommitDegradesToUnknown() {
        BuildIdentity id = new BuildIdentity("life-engine-runtime", "unknown", "local", null, null, null, null);

        assertThat(firstMeterAfterTagging(id).getId().getTag("commit")).isEqualTo("unknown");
    }
}
