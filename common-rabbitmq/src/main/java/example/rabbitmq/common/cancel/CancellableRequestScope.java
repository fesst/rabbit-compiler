package example.rabbitmq.common.cancel;

/**
 * Scope in which a single active request of a given type is allowed. Requests
 * with the same type but different scopes do not cancel each other.
 */
public record CancellableRequestScope(ResourceId resourceId) {

    public static CancellableRequestScope of(ResourceId resourceId) {
        return new CancellableRequestScope(resourceId);
    }
}
