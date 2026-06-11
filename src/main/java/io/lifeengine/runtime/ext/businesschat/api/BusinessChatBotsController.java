package io.lifeengine.runtime.ext.businesschat.api;

import io.lifeengine.runtime.ext.businesschat.BusinessBotDefinition;
import io.lifeengine.runtime.ext.businesschat.BusinessBotNotFoundException;
import io.lifeengine.runtime.ext.businesschat.BusinessBotRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/runtime/business-chat/bots")
@ConditionalOnProperty(
        name = "lifeengine.runtime.ext.business-chat.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BusinessChatBotsController {

    private final BusinessBotRegistry botRegistry;

    public BusinessChatBotsController(BusinessBotRegistry botRegistry) {
        this.botRegistry = botRegistry;
    }

    @GetMapping("/{botId}")
    public Mono<BusinessBotProfileView> getBot(@PathVariable String botId) {
        return Mono.fromCallable(() -> toView(botRegistry.require(botId)));
    }

    @ExceptionHandler(BusinessBotNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Mono<ApiError> notFound(BusinessBotNotFoundException ex) {
        return Mono.just(new ApiError("not_found", ex.getMessage()));
    }

    private static BusinessBotProfileView toView(BusinessBotDefinition bot) {
        return new BusinessBotProfileView(
                bot.botId(),
                bot.businessName(),
                bot.tone(),
                List.copyOf(bot.rules()),
                bot.faqs().stream()
                        .map(faq -> new BusinessBotProfileView.FaqView(faq.question(), faq.answer()))
                        .toList(),
                bot.suggestedPrompts().stream()
                        .map(prompt -> new BusinessBotProfileView.SuggestedPromptView(
                                prompt.label(), prompt.message()))
                        .toList());
    }

    public record ApiError(String code, String message) {}
}
