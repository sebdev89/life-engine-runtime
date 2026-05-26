package io.lifeengine.runtime.extension;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads optional {@link RuntimeModule} beans from the Spring context (future vertical JARs).
 * life-engine-runtime ships no vertical implementations.
 */
@Component
public class RuntimeModuleLoader {

    private static final Logger log = LoggerFactory.getLogger(RuntimeModuleLoader.class);

    public RuntimeModuleLoader(RuntimeRegistry registry, List<RuntimeModule> modules) {
        for (RuntimeModule module : modules) {
            log.info("Loading runtime module {}", module.moduleId());
            module.register(registry);
        }
    }
}
