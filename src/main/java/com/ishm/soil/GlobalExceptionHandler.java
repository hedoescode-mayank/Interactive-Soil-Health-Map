package com.ishm.soil;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.hateoas.JsonError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Error(global = true, exception = ConstraintViolationException.class)
    public HttpResponse<Map<String, Object>> handleValidationException(HttpRequest<?> request, ConstraintViolationException exception) {
        LOG.warn("Validation error for request {}: {}", request.getPath(), exception.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", "Validation failed");
        
        Map<String, String> details = exception.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage(),
                        (v1, v2) -> v1
                ));
        
        response.put("details", details);
        return HttpResponse.badRequest(response);
    }

    @Error(global = true, exception = RuntimeException.class)
    public HttpResponse<JsonError> handleRuntimeException(HttpRequest<?> request, RuntimeException exception) {
        LOG.error("Unhandled runtime exception for request {}: {}", request.getPath(), exception.getMessage(), exception);
        return HttpResponse.serverError(new JsonError("An internal server error occurred: " + exception.getMessage()));
    }
}
