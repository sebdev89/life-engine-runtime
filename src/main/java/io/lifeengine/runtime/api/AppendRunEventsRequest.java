package io.lifeengine.runtime.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AppendRunEventsRequest(@NotEmpty @Valid List<AppendRunEventRequest> events) {}
