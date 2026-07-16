package io.lifeengine.runtime.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.format.DateTimeFormatter;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

/**
 * Unit coverage for the source-precedence rules of {@link BuildIdentityResolver} (no Spring context, no
 * Docker). The two runtime paths are: git.properties present (local + CI) and build-arg overrides (the
 * Docker image, which has no {@code .git}).
 */
class BuildIdentityResolverTest {

    private static GitProperties git(String abbrev, String branch, long commitEpochMillis) {
        Properties p = new Properties();
        p.setProperty("commit.id.abbrev", abbrev);
        p.setProperty("commit.id", abbrev + "00000000000000000000000000000000000");
        p.setProperty("branch", branch);
        p.setProperty("commit.time", String.valueOf(commitEpochMillis));
        return new GitProperties(p);
    }

    private static BuildProperties build(String version, long buildEpochMillis) {
        Properties p = new Properties();
        p.setProperty("group", "io.lifeengine");
        p.setProperty("artifact", "life-engine-runtime");
        p.setProperty("name", "life-engine-runtime");
        p.setProperty("version", version);
        p.setProperty("time", String.valueOf(buildEpochMillis));
        return new BuildProperties(p);
    }

    @Test
    @DisplayName("git.properties present, no override → identity from GitProperties + BuildProperties")
    void resolvesFromProperties() {
        GitProperties g = git("215e56a", "main", 1_752_379_886_000L);
        BuildProperties b = build("0.1.0-SNAPSHOT", 1_752_379_974_000L);

        BuildIdentity id = BuildIdentityResolver.resolve("life-engine-runtime", "uat", "", "", "", g, b);

        assertThat(id.serviceName()).isEqualTo("life-engine-runtime");
        assertThat(id.serviceVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(id.environment()).isEqualTo("uat");
        assertThat(id.gitCommit()).isEqualTo("215e56a");
        assertThat(id.gitBranch()).isEqualTo("main");
        assertThat(g.getCommitTime()).isNotNull();
        assertThat(id.gitCommitTime()).isEqualTo(DateTimeFormatter.ISO_INSTANT.format(g.getCommitTime()));
        assertThat(b.getTime()).isNotNull();
        assertThat(id.buildTimestamp()).isEqualTo(DateTimeFormatter.ISO_INSTANT.format(b.getTime()));
    }

    @Test
    @DisplayName("override (Docker build-arg path) wins over git.properties and abbreviates a full sha to 7")
    void overridesWinAndAbbreviate() {
        GitProperties g = git("aaaaaaa", "feature", 1_752_379_886_000L);
        BuildProperties b = build("1.2.3", 1_752_379_974_000L);

        BuildIdentity id =
                BuildIdentityResolver.resolve(
                        "life-engine-runtime",
                        "prod",
                        "82446cf9373f353a78d304697a7639f1110a8413",
                        "release",
                        "2026-07-16T02:50:46Z",
                        g,
                        b);

        assertThat(id.gitCommit()).isEqualTo("82446cf");
        assertThat(id.gitBranch()).isEqualTo("release");
        assertThat(id.gitCommitTime()).isEqualTo("2026-07-16T02:50:46Z");
        assertThat(id.serviceVersion()).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("no git.properties and no override (bare image) → git fields null; build timestamp still set")
    void noGitYieldsNulls() {
        BuildProperties b = build("0.1.0-SNAPSHOT", 1_752_379_974_000L);

        BuildIdentity id = BuildIdentityResolver.resolve("life-engine-runtime", "local", "", "", "", null, b);

        assertThat(id.gitCommit()).isNull();
        assertThat(id.gitBranch()).isNull();
        assertThat(id.gitCommitTime()).isNull();
        assertThat(id.buildTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("no build-info → serviceVersion falls back to 'unknown', buildTimestamp null")
    void noBuildYieldsUnknownVersion() {
        GitProperties g = git("215e56a", "main", 1_752_379_886_000L);

        BuildIdentity id = BuildIdentityResolver.resolve("life-engine-runtime", "local", "", "", "", g, null);

        assertThat(id.serviceVersion()).isEqualTo("unknown");
        assertThat(id.buildTimestamp()).isNull();
        assertThat(id.gitCommit()).isEqualTo("215e56a");
    }

    @Test
    @DisplayName("environment comes straight from the provided config value (never hardcoded)")
    void environmentPassthrough() {
        BuildIdentity id = BuildIdentityResolver.resolve("life-engine-runtime", "staging", "", "", "", null, null);

        assertThat(id.environment()).isEqualTo("staging");
    }
}
