package com.pdfgen.adapters.web;

import com.pdfgen.application.DocumentNotFoundException;
import com.pdfgen.application.PhotoLibrary;
import com.pdfgen.application.VersionConflictException;
import com.pdfgen.domain.DocumentException;
import com.pdfgen.domain.mapping.DocumentValidationException;
import com.pdfgen.domain.mapping.Problem;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Maps document problems to JSON for every API endpoint:
 * 404 unknown document, 409 changed on disk, 422 invalid (with id-based field paths for the editor).
 */
@RestControllerAdvice
public class ApiErrors {

    public record ErrorBody(String error, List<Problem> problems) {
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ErrorBody> notFound(DocumentNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage(), List.of());
    }

    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<ErrorBody> conflict(VersionConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), List.of());
    }

    @ExceptionHandler(DocumentValidationException.class)
    ResponseEntity<ErrorBody> invalid(DocumentValidationException e) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), e.details());
    }

    @ExceptionHandler(PhotoLibrary.UnsupportedImageException.class)
    ResponseEntity<ErrorBody> unsupportedImage(PhotoLibrary.UnsupportedImageException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage(), List.of());
    }

    @ExceptionHandler(PhotoLibrary.ImageTooLargeException.class)
    ResponseEntity<ErrorBody> imageTooLarge(PhotoLibrary.ImageTooLargeException e) {
        return body(HttpStatus.CONTENT_TOO_LARGE, e.getMessage(), List.of());
    }

    @ExceptionHandler(DocumentException.class)
    ResponseEntity<ErrorBody> unprocessable(DocumentException e) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), List.of());
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus status, String message, List<Problem> problems) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(message, problems));
    }
}
