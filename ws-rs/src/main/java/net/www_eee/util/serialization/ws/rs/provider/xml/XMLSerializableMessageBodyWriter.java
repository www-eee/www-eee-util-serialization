/*
 * Copyright 2017-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, plus additional permissions, a copy of which you should have
 * received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.ws.rs.provider.xml;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import javax.xml.stream.*;

import org.eclipse.jdt.annotation.*;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.*;

import net.www_eee.util.serialization.xml.*;


/**
 * A {@link MessageBodyWriter} implementation for {@link XMLSerializable} objects.
 */
@NonNullByDefault
public class XMLSerializableMessageBodyWriter implements MessageBodyWriter<XMLSerializable> {
  /**
   * The {@link Charset} Object for the <code>"UTF-8"</code> charset.
   */
  protected static final Charset UTF_8_CHARSET = Charset.forName("UTF-8");
  /**
   * An {@link XMLOutputFactory} for internal use writing responses.
   */
  private static final XMLOutputFactory XML_OUTPUT_FACTORY = XMLOutputFactory.newInstance();

  @Override
  public final boolean isWriteable(final Class<?> type, final Type genericType, final @NonNull Annotation[] annotations, final MediaType mediaType) {
    return XMLSerializable.class.isAssignableFrom(type);
  }

  @Override
  public final long getSize(final XMLSerializable entity, final Class<?> type, final Type genericType, final @NonNull Annotation[] annotations, final MediaType mediaType) {
    return -1;
  }

  /**
   * Write the given {@link XMLSerializable} entity to the supplied {@link XMLStreamWriter}.
   * 
   * @param entity The {@link XMLSerializable} entity to write.
   * @param streamWriter The {@link XMLStreamWriter} to write to.
   * @param parentNamespace The namespace {@link URI} currently set as default on the supplied {@link XMLStreamWriter}.
   * @throws XMLStreamException If there was a problem writing the XML.
   */
  protected void writeXML(final XMLSerializable entity, final XMLStreamWriter streamWriter, @Nullable URI parentNamespace) throws XMLStreamException {
    entity.writeXML(streamWriter, parentNamespace);
    return;
  }

  @Override
  public final void writeTo(final XMLSerializable entity, final Class<?> type, final Type genericType, final @NonNull Annotation[] annotations, final MediaType mediaType, final MultivaluedMap<String,Object> httpHeaders, final OutputStream entityStream) throws IOException, WebApplicationException {
    try {
      final XMLStreamWriter streamWriter = XML_OUTPUT_FACTORY.createXMLStreamWriter(entityStream, UTF_8_CHARSET.name());
      streamWriter.writeStartDocument();
      writeXML(entity, streamWriter, null);
      streamWriter.writeEndDocument();
    } catch (XMLStreamException xmlse) {
      final Throwable cause = xmlse.getCause();
      if (cause instanceof IOException) throw Objects.requireNonNull(IOException.class.cast(cause));
      throw new WebApplicationException(xmlse);
    }
    return;
  }

}
