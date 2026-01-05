package com.exittrading.app.exception;

import com.exittrading.app.dto.ErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

/**
 * Global exception handler to centralize error responses.
 * Catches common exceptions and returns a consistent JSON structure.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex, WebRequest request) {
        log.warn("Bad Request: {}", ex.getMessage());
        ErrorResponse err = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );
        return new ResponseEntity<>(err, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException ex, WebRequest request) {
        log.warn("Not Found: {}", ex.getMessage());
        ErrorResponse err = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );
        return new ResponseEntity<>(err, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex, WebRequest request) {
        log.warn("Conflict: {}", ex.getMessage());
        ErrorResponse err = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );
        return new ResponseEntity<>(err, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobal(Exception ex, WebRequest request) {
        // Suppress common client-side disconnect errors to avoid log noise
        if (isClientAbort(ex)) {
             log.debug("Client aborted connection or stream closed: {}", ex.getMessage());
             return null; // Cannot write to a closed stream
        }
        
        log.error("Internal Error: ", ex);
        ErrorResponse err = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "An unexpected error occurred. Please contact support.",
                request.getDescription(false).replace("uri=", "")
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                             .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                             .body(err);
    }


    private boolean isClientAbort(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) msg = "";
        
        // Check for common specific exceptions by class name first (avoid tight coupling if classes not imported)
        String cls = ex.getClass().getName();
        if (cls.contains("ClientAbortException") || 
            cls.contains("ClosedChannelException") ||
            cls.contains("AsyncRequestNotUsableException")) {
            return true;
        }

        // Recursive check for causes
        if (ex.getCause() instanceof Exception && ex.getCause() != ex) {
            if (isClientAbort((Exception) ex.getCause())) return true;
        }

        // String checks on class or message
        return msg.contains("broken pipe") || 
               msg.contains("connection reset") || 
               msg.contains("aborted") ||
               msg.contains("Connection reset by peer");
    }
}
