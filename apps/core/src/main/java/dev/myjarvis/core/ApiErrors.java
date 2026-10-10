package dev.myjarvis.core;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice
final class ApiErrors {
 @ExceptionHandler(ApiException.class) ResponseEntity<?> known(ApiException e,HttpServletRequest r){return error(e.status,e.code,r);}
 @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class) ResponseEntity<?> invalid(Exception e,HttpServletRequest r){return error(400,"INVALID_REQUEST",r);}
 @ExceptionHandler({org.springframework.web.servlet.NoHandlerFoundException.class,org.springframework.web.servlet.resource.NoResourceFoundException.class}) ResponseEntity<?> notFound(Exception e,HttpServletRequest r){return error(404,"NOT_FOUND",r);}
 @ExceptionHandler(Exception.class) ResponseEntity<?> hidden(Exception e,HttpServletRequest r){return error(500,"INTERNAL_ERROR",r);}
 private ResponseEntity<?> error(int status,String code,HttpServletRequest r){return ResponseEntity.status(status).body(Map.of("error",Map.of("code",code,"message",code,"retryable",false,"details",Map.of()),"request_id",r.getAttribute("request_id")));}
}
