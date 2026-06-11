package io.lifeengine.runtime.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StrictAgentJsonLeadCaptureTest {

    @Test
    void parseLeadCapture_acceptsCapturedLead() {
        var out =
                StrictAgentJson.parseLeadCapture(
                        """
                        {
                          "leadCaptured": true,
                          "leadData": {
                            "nombre": "María López",
                            "telefono": "11 4444-9999",
                            "email": null
                          }
                        }
                        """);

        assertThat(out.leadCaptured()).isTrue();
        assertThat(out.leadData().nombre()).isEqualTo("María López");
        assertThat(out.leadData().telefono()).isEqualTo("11 4444-9999");
        assertThat(out.leadData().email()).isNull();
    }

    @Test
    void parseLeadCapture_acceptsEmptyLead() {
        var out =
                StrictAgentJson.parseLeadCapture(
                        """
                        {
                          "leadCaptured": false,
                          "leadData": {
                            "nombre": null,
                            "telefono": null,
                            "email": null
                          }
                        }
                        """);

        assertThat(out.leadCaptured()).isFalse();
        assertThat(out.leadData().isEmpty()).isTrue();
    }

    @Test
    void parseLeadCapture_rejectsCapturedWithoutData() {
        assertThatThrownBy(
                        () ->
                                StrictAgentJson.parseLeadCapture(
                                        """
                                        {
                                          "leadCaptured": true,
                                          "leadData": {
                                            "nombre": null,
                                            "telefono": null,
                                            "email": null
                                          }
                                        }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leadCaptured=true");
    }

    @Test
    void parseLeadCapture_rejectsDataWithoutCapturedFlag() {
        assertThatThrownBy(
                        () ->
                                StrictAgentJson.parseLeadCapture(
                                        """
                                        {
                                          "leadCaptured": false,
                                          "leadData": {
                                            "nombre": "Ana",
                                            "telefono": null,
                                            "email": null
                                          }
                                        }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leadCaptured=false");
    }
}
