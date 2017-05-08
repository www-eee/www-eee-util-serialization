/*
 * Copyright 2016-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU LESSER GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.parser.xml.soap;

import java.beans.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

import javax.xml.ws.soap.*;

import org.eclipse.jdt.annotation.*;

import org.junit.*;
import org.junit.rules.*;

import static org.junit.Assert.*;


/**
 * JUnit tests for {@link SOAPStreamParser}.
 */
@NonNullByDefault
public class SOAPStreamParserTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();
  public static final SOAPStreamParser<Departure> DEPARTURE_STREAM_PARSER;
  static {
    final SOAPStreamParser.SchemaBuilder<? extends SOAPStreamParser.SchemaBuilder<?>> schema = SOAPStreamParser.buildSchema(URI.create("http://www_eee.net/ns/"));
    schema.defineSimpleElement("departureYear", Year.class, (ctx, value) -> Year.parse(value), true).defineHeaderElementWithChildBuilder().addReferencedElementAsChild("departureYear").completeElementDefinition().defineStringElement("departing").defineSimpleElement("departureMonthDay", MonthDay.class, MonthDay::parse);
    schema.defineElementWithInjectedTargetBuilder("departure", Departure.class).injectChildObject("Departing", "departing").injectChildObject("DepartureMonthDay", "departureMonthDay").injectSavedObject("DepartureYear", "departureYear").completeElementDefinition();
    // schema.defineElementWithChildBuilder("departure", Departure.class, (ctx) -> new Departure(ctx.getRequiredChildValue("departing", String.class), ctx.getRequiredChildValue("departureMonthDay", MonthDay.class).atYear(ctx.getRequiredSavedValue("departureYear", Year.class).getValue()))).addReferencedElementAsChild("departing").addReferencedElementAsChild("departureMonthDay").completeElementDefinition();
    schema.defineContainerElementWithChildBuilder("departures").addReferencedElementAsChild("departure").addReferencedElementAsChild(SOAPStreamParser.FAULT_QNAME).completeElementDefinition().defineBodyElement("departures");
    DEPARTURE_STREAM_PARSER = schema.defineEnvelopeElement(true).createParser(Departure.class, "departure");
  }

  /**
   * Test parsing the data while ignoring extra elements.
   */
  @Test
  public void testIgnoreExtra() throws Exception {
    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_ignore_extra.xml");
    System.err.println(DEPARTURE_STREAM_PARSER.parse(testURL.openStream()).collect(Collectors.toList()));
    final Stream<Departure> departures = DEPARTURE_STREAM_PARSER.parse(testURL.openStream());
    assertEquals("Canada[2001-01-01],USA[2001-02-01],Australia[2001-03-01]", departures.map(Object::toString).collect(Collectors.joining(",")));
    return;
  }

  /**
   * Test global (top-level) fault handling.
   */
  @Test
  public void testGlobalFaultThrown() throws Exception {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Server went boom.");

    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_global_fault.xml");
    try {
      DEPARTURE_STREAM_PARSER.parse(testURL.openStream());
    } catch (SOAPStreamParser.ElementValueParsingException evpe) {
      throw evpe.getCause();
    }
    return;
  }

  /**
   * Test local fault handling.
   */
  @Test
  public void testLocalFaultThrown() throws Exception {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Invalid departure record.");

    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_local_fault.xml");
    try {
      final Iterator<Departure> departures = DEPARTURE_STREAM_PARSER.parse(testURL.openStream()).iterator();
      assertEquals("Canada[2001-01-01]", departures.next().toString());
      assertEquals("USA[2001-02-01]", departures.next().toString());
      departures.next().toString();
    } catch (SOAPStreamParser.ElementValueParsingException evpe) {
      throw evpe.getCause();
    }
    return;
  }

  /**
   * Test local fault recovery (can you continue iterating afterwards).
   */
  @Test
  public void testLocalFaultRecovery() throws Exception {
    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_local_fault.xml");
    final Iterator<Departure> departures = DEPARTURE_STREAM_PARSER.parse(testURL.openStream()).iterator();
    assertEquals("Canada[2001-01-01]", departures.next().toString());
    assertEquals("USA[2001-02-01]", departures.next().toString());
    try {
      departures.next().toString();
      fail("Expected SOAPFaultException");
    } catch (SOAPStreamParser.ElementValueParsingException evpe) {}
    assertEquals("Australia[2001-03-01]", departures.next().toString());

    return;
  }

  public static class Departure {
    private final String departing;
    private final LocalDate date;

    public Departure(final String departing, final LocalDate date) {
      this.departing = departing;
      this.date = date;
      return;
    }

    @ConstructorProperties({ "Departing", "DepartureMonthDay", "DepartureYear" })
    public Departure(final String departing, final MonthDay departureMonthDay, final @Nullable Year departureYear) {
      this(departing, departureMonthDay.atYear((departureYear != null) ? departureYear.getValue() : LocalDateTime.now().getYear()));
      return;
    }

    @Override
    public String toString() {
      return departing + '[' + date + ']';
    }

  } // Departure

}
