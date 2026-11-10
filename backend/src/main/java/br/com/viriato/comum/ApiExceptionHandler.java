package br.com.viriato.comum;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> erro(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("status", status.value(), "erro", msg));
    }

    @ExceptionHandler(NaoEncontradoException.class)
    ResponseEntity<Map<String, Object>> naoEncontrado(NaoEncontradoException e) {
        return erro(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NegocioException.class)
    ResponseEntity<Map<String, Object>> negocio(NegocioException e) {
        return erro(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<Map<String, Object>> credenciais(BadCredentialsException e) {
        return erro(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> negado(AccessDeniedException e) {
        return erro(HttpStatus.FORBIDDEN, "Sem permissão para esta ação");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> grande(MaxUploadSizeExceededException e) {
        return erro(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo excede o tamanho máximo permitido");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validacao(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return erro(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String, Object>> violacao(ConstraintViolationException e) {
        return erro(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> generico(Exception e) {
        log.error("Erro inesperado", e);
        return erro(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");
    }
}
