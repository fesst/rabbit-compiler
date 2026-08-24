package example.rabbitmq.sourcechanger.dto;

import java.util.List;

/** Code-completion result. */
public record CompletionResultDto(boolean success, String message, List<String> suggestions) {
}
