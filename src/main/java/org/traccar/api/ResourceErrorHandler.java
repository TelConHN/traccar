/*
 * Copyright 2015 - 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class ResourceErrorHandler implements ExceptionMapper<Exception> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceErrorHandler.class);

    private static boolean isConcurrencyWrapper(Throwable throwable) {
        return throwable instanceof CompletionException || throwable instanceof ExecutionException;
    }

    @Override
    public Response toResponse(Exception exception) {
        Throwable throwable = exception;
        while (isConcurrencyWrapper(throwable) && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        LOGGER.warn("API request failed", throwable);

        // Only the message goes to the client; the stack trace stays in the log (web.showStackTraces is not
        // honored on purpose, see the 2026-08 security remediation).
        String message = throwable.getMessage();
        if (throwable instanceof WebApplicationException webException) {
            return Response.fromResponse(webException.getResponse()).entity(message).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity(message).build();
        }
    }

}
