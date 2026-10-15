package app.clinicreminders.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when a record doesn't exist or belongs to another clinic; callers can't tell which. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {

    public NotFoundException(String type, Object id) {
        super(type + " " + id + " not found");
    }
}
