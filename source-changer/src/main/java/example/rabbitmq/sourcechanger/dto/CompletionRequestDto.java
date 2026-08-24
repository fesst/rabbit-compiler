package example.rabbitmq.sourcechanger.dto;

/** Code-completion request. */
public record CompletionRequestDto(String resourceId, int line, int column, String text) {
}
