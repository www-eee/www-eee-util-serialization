/*
 * Copyright 2017-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, plus additional permissions, a copy of which you should have
 * received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.ws.rs.provider.xml.soap;

import java.nio.charset.*;

import javax.naming.*;
import javax.naming.directory.*;
import javax.xml.soap.*;

import org.eclipse.jdt.annotation.*;

import net.www_eee.util.serialization.xml.*;

import javax.ws.rs.core.*;
import javax.ws.rs.ext.*;


/**
 * An {@link ExceptionMapper} implementation which will return
 * {@linkplain XMLSerializable#createSOAPFaultWrapper(Throwable, boolean) SOAP Fault XML} for exceptions.
 */
@NonNullByDefault
public class SOAPFaultExceptionMapper implements ExceptionMapper<Throwable> {
  /**
   * The {@link Charset} Object for the <code>"UTF-8"</code> charset.
   */
  protected static final Charset UTF_8_CHARSET = Charset.forName("UTF-8");
  /**
   * The {@link MediaType} Object for the <code>"application/soap+xml"</code> mime type.
   */
  protected static final MediaType SOAP_1_2_CONTENT_TYPE = MediaType.valueOf(SOAPConstants.SOAP_1_2_CONTENT_TYPE);

  protected Response.StatusType getStatus(final Throwable throwable) {
    if (throwable instanceof NameNotFoundException) {
      return Response.Status.NOT_FOUND;
    } else if (throwable instanceof OperationNotSupportedException) {
      return Response.Status.NOT_IMPLEMENTED;
    } else if (throwable instanceof javax.naming.ServiceUnavailableException) {
      return Response.Status.SERVICE_UNAVAILABLE;
    } else if ((throwable instanceof IllegalArgumentException) || (throwable instanceof InvalidAttributeIdentifierException)) {
      return Response.Status.BAD_REQUEST;
    }
    return Response.Status.INTERNAL_SERVER_ERROR;
  }

  @Override
  public Response toResponse(final Throwable throwable) {
    final Response.StatusType status = getStatus(throwable);
    return Response.status(status).encoding(UTF_8_CHARSET.name()).type(SOAP_1_2_CONTENT_TYPE).entity(XMLSerializable.createSOAPFaultWrapper(throwable, status.getStatusCode() < 500)).build();
  }

}
