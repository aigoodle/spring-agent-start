package io.github.aigoodle.agent.strategy;

import io.github.aigoodle.agent.api.AgentMessage;
import io.github.aigoodle.agent.api.AgentResponse;
import io.github.aigoodle.agent.api.AgentStep;
import io.github.aigoodle.agent.api.AgentStrategyType;
import io.github.aigoodle.common.util.JsonUtils;
import io.github.aigoodle.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan-and-execute strategy: the model first decomposes the task into a list of steps,
 * each step is then executed (optionally invoking a tool), and finally the model
 * synthesises an answer from the step results. Better than ReAct for multi-step tasks
 * where an explicit plan helps.
 */
public class PlanExecuteStrategy implements AgentStrategy {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteStrategy.class);

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*]", Pattern.DOTALL);
    private static final Pattern ACTION = Pattern.compile("Action\\s*:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT =
            Pattern.compile("Action\\s*Input\\s*:\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public AgentStrategyType type() {
        return AgentStrategyType.PLAN_EXECUTE;
    }

    @Override
    public AgentResponse run(AgentRunContext ctx) {
        Map<String, AgentTool> tools = new LinkedHashMap<>();
        ctx.getTools().forEach(t -> tools.put(t.name(), t));
        ChatClient client = ctx.getChatClient();
        org.springframework.ai.chat.prompt.ChatOptions perApp =
                AgentChatOptionsFactory.build(ctx.getDefinition());
        AgentResponse response = new AgentResponse();
        response.setConversationId(ctx.getConversationId());

        // ---- 1. PLAN ----
        String planPrompt = ctx.getQuery()
                + "\n\nFirst, produce a plan as a JSON array of short step strings. Output ONLY the JSON array.";
        String planText = call(client, system(ctx, tools.values()), ctx.getHistory(), planPrompt, perApp);
        List<String> steps = parsePlan(planText, ctx.getQuery());
        AgentStep planStep = AgentStep.of(AgentStep.Kind.THOUGHT, "Plan: " + steps);
        response.addStep(planStep);
        ctx.fireStep(planStep);

        // ---- 2. EXECUTE ----
        StringBuilder scratchpad = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            String execPrompt = "Execute step: " + step
                    + "\nIf a tool is needed, reply with:\nAction: <tool name>\nAction Input: <JSON args>\n"
                    + "Otherwise reply with the result text directly.";
            String execText = call(client, system(ctx, tools.values()), List.of(), execPrompt, perApp);
            String result = executeStep(execText, tools, response, ctx);
            scratchpad.append("Step ").append(i + 1).append(": ").append(step)
                    .append(" => ").append(result).append("\n");
        }

        // ---- 3. SYNTHESIZE ----
        String synthPrompt = "Task: " + ctx.getQuery() + "\n\nStep results:\n" + scratchpad
                + "\nProduce the final answer for the user.";
        String finalText = call(client, system(ctx, tools.values()), List.of(), synthPrompt, perApp);
        AgentStep finalStep = AgentStep.of(AgentStep.Kind.FINAL, finalText);
        response.addStep(finalStep);
        ctx.fireStep(finalStep);
        response.setText(finalText);
        response.setStatus(AgentResponse.Status.COMPLETED);
        return response;
    }

    private String executeStep(String execText, Map<String, AgentTool> tools, AgentResponse response,
                               AgentRunContext ctx) {
        Matcher action = ACTION.matcher(execText);
        if (!action.find()) {
            return execText.strip();
        }
        String toolName = firstLine(action.group(1));
        Matcher input = ACTION_INPUT.matcher(execText);
        String args = input.find() ? input.group(1).strip() : "{}";
        AgentStep step = new AgentStep();
        step.setKind(AgentStep.Kind.ACTION);
        step.setAction(toolName);
        step.setActionInput(args);
        response.addStep(step);
        ctx.fireStep(step);

        AgentTool tool = tools.get(toolName);
        String observation;
        if (tool == null) {
            observation = "error: unknown tool '" + toolName + "'";
        } else {
            try {
                Map<String, Object> argMap = JsonUtils.parseMap(args);
                Object result = tool.execute(argMap == null ? Map.of() : argMap);
                observation = result == null ? "" : String.valueOf(result);
            } catch (Exception e) {
                observation = "error: " + e.getMessage();
            }
        }
        AgentStep obs = new AgentStep();
        obs.setKind(AgentStep.Kind.OBSERVATION);
        obs.setObservation(observation);
        response.addStep(obs);
        ctx.fireStep(obs);
        return observation;
    }

    private List<String> parsePlan(String planText, String fallbackTask) {
        if (planText != null) {
            Matcher m = JSON_ARRAY.matcher(planText);
            if (m.find()) {
                try {
                    List<String> steps = JsonUtils.parseList(m.group(), String.class);
                    if (steps != null && !steps.isEmpty()) {
                        return steps;
                    }
                } catch (Exception e) {
                    log.debug("Plan JSON parse failed, falling back to single step: {}", e.getMessage());
                }
            }
        }
        return List.of(fallbackTask);
    }

    private static String call(ChatClient client, SystemMessage system, List<AgentMessage> history,
                               String userText, org.springframework.ai.chat.prompt.ChatOptions perApp) {
        List<Message> messages = new ArrayList<>();
        messages.add(system);
        for (AgentMessage h : history) {
            messages.add(switch (h.role()) {
                case ASSISTANT -> new AssistantMessage(h.content());
                case SYSTEM -> new SystemMessage(h.content());
                default -> new UserMessage(h.content());
            });
        }
        messages.add(new UserMessage(userText));
        var spec = client.prompt().messages(messages);
        if (perApp != null) {
            spec = spec.options(perApp);
        }
        String content = spec.call().content();
        return content == null ? "" : content;
    }

    private static SystemMessage system(AgentRunContext ctx, Iterable<AgentTool> tools) {
        StringBuilder sb = new StringBuilder(
                ctx.getDefinition().getInstructions() == null ? "You are a capable planning agent."
                        : ctx.getDefinition().getInstructions());
        sb.append("\n\nAvailable tools:\n");
        for (AgentTool t : tools) {
            sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n");
        }
        return new SystemMessage(sb.toString());
    }

    private static String firstLine(String s) {
        String line = s.strip();
        int nl = line.indexOf('\n');
        return (nl >= 0 ? line.substring(0, nl) : line).strip();
    }
}
