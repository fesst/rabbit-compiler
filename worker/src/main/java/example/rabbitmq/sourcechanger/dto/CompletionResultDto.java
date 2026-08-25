package example.rabbitmq.sourcechanger.dto;

import java.util.List;

/** Code-completion result (mirror of the source-changer DTO). */
public record CompletionResultDto(boolean success, String message, List<String> suggestions) {
}
