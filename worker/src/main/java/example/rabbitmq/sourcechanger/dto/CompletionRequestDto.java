package example.rabbitmq.sourcechanger.dto;

/** Code-completion request (mirror of the source-changer DTO). */
public record CompletionRequestDto(String resourceId, int line, int column, String text) {
}
