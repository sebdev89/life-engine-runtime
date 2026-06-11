package io.lifeengine.runtime.ext.businesschat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable {@code business-chat.reply.v1} stage output contract (Runtime side). */
public final class BusinessChatReplyContractV1 {

  public static final String CONTRACT_VERSION = "v1";
  public static final String DEFAULT_CONVERSATION_STATE = "NEW";
  public static final String DEFAULT_LEAD_SCORE = "COLD";
  public static final String DEFAULT_CONFIDENCE = "MEDIUM";
  public static final String DEFAULT_CONFIDENCE_REASON = "sin_evaluacion";

  private BusinessChatReplyContractV1() {}

  public static void applyToReplyOutput(
          ObjectMapper mapper,
          ObjectNode replyNode,
          String sourceInputJson,
          String businessContextJson,
          BusinessReplyConfidenceService.ReplyConfidence confidence) {
    try {
      JsonNode source = mapper.readTree(blankJson(sourceInputJson));
      JsonNode workflowContext = source.path("businessContext");
      JsonNode stageContext = mapper.readTree(blankJson(businessContextJson));

      String reply = text(replyNode, "response");
      if (reply.isBlank()) {
        reply = text(replyNode, "reply");
      }
      String intent = firstText(replyNode, stageContext, "intent");
      String conversationState = firstText(workflowContext, stageContext, "conversationState");
      if (conversationState.isBlank()) {
        conversationState = DEFAULT_CONVERSATION_STATE;
      }

      String confidenceLevel =
              confidence != null ? confidence.level() : text(replyNode, "confidence");
      if (confidenceLevel.isBlank()) {
        confidenceLevel = DEFAULT_CONFIDENCE;
      }
      String confidenceReason =
              confidence != null ? confidence.reason() : text(replyNode, "confidenceReason");
      if (confidenceReason.isBlank()) {
        confidenceReason = DEFAULT_CONFIDENCE_REASON;
      }

      Map<String, Object> lead = readLead(workflowContext);
      String leadScore = firstText(workflowContext, stageContext, "leadScore");
      if (leadScore.isBlank()) {
        leadScore = DEFAULT_LEAD_SCORE;
      }
      String leadScoreReason = firstText(workflowContext, stageContext, "leadScoreReason");

      Map<String, Object> handoff = readHandoff(workflowContext, stageContext, replyNode);

      replyNode.put("reply", reply);
      if (!replyNode.has("response") && !reply.isBlank()) {
        replyNode.put("response", reply);
      }
      replyNode.put("intent", intent);
      replyNode.put("conversationState", conversationState);
      replyNode.put("confidence", confidenceLevel);
      replyNode.put("confidenceReason", confidenceReason);
      replyNode.set("lead", mapper.valueToTree(lead));
      replyNode.put("leadScore", leadScore);
      replyNode.put("leadScoreReason", leadScoreReason);
      replyNode.set("handoff", mapper.valueToTree(handoff));
      replyNode.put("contractVersion", CONTRACT_VERSION);
    } catch (Exception ignored) {
      // Contract enrichment is best-effort; a malformed payload must not fail the run.
    }
  }

  private static Map<String, Object> readLead(JsonNode workflowContext) {
    Map<String, Object> lead = new LinkedHashMap<>();
    JsonNode leadData = workflowContext.path("leadData");
    if (!leadData.isObject()) {
      return Map.copyOf(lead);
    }
    putMapped(lead, leadData, "name", "nombre");
    putMapped(lead, leadData, "phone", "telefono");
    putMapped(lead, leadData, "email", "email");
    putMapped(lead, leadData, "reason", "motivo");
    putMapped(lead, leadData, "preferredTime", "preferredTime");
    return Map.copyOf(lead);
  }

  private static Map<String, Object> readHandoff(
          JsonNode workflowContext, JsonNode stageContext, JsonNode replyNode) {
    JsonNode decision = workflowContext.path("handoffDecision");
    if (decision.isObject()) {
      return handoffFromDecision(decision);
    }
    boolean required = replyNode.path("handoffRequired").asBoolean(false)
            || stageContext.path("handoffRequired").asBoolean(false);
    if (!required) {
      return defaultHandoff();
    }
    String reason = text(stageContext, "handoffReason");
    if (reason.isBlank()) {
      reason = "runtime_handoff";
    }
    Map<String, Object> handoff = new LinkedHashMap<>();
    handoff.put("required", true);
    handoff.put("reason", reason);
    handoff.put("priority", "MEDIUM");
    return Map.copyOf(handoff);
  }

  private static Map<String, Object> handoffFromDecision(JsonNode decision) {
    Map<String, Object> handoff = new LinkedHashMap<>();
    handoff.put("required", decision.path("required").asBoolean(false));
    handoff.put("reason", text(decision, "reason"));
    handoff.put("priority", text(decision, "priority"));
    String team = text(decision, "suggestedTeam");
    if (!team.isBlank()) {
      handoff.put("suggestedTeam", team);
    }
    return Map.copyOf(handoff);
  }

  private static Map<String, Object> defaultHandoff() {
    Map<String, Object> handoff = new LinkedHashMap<>();
    handoff.put("required", false);
    handoff.put("reason", "no_requerido");
    handoff.put("priority", "LOW");
    return Map.copyOf(handoff);
  }

  private static void putMapped(
          Map<String, Object> target, JsonNode source, String targetKey, String... sourceKeys) {
    for (String sourceKey : sourceKeys) {
      String value = text(source, sourceKey);
      if (!value.isBlank()) {
        target.put(targetKey, value);
        return;
      }
    }
  }

  private static String firstText(JsonNode primary, JsonNode secondary, String field) {
    String value = text(primary, field);
    if (!value.isBlank()) {
      return value;
    }
    return text(secondary, field);
  }

  private static String text(JsonNode node, String field) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asText("").trim();
  }

  private static String blankJson(String json) {
    return json == null || json.isBlank() ? "{}" : json;
  }
}
